"""Immutable, chunked HR archives. An activity links only to a completed archive or tombstone."""
import base64
import binascii
import json
from typing import Literal
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from sqlalchemy import delete, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from app.auth import require_device
from app.database import get_db
from app.models.activity import Device
from app.models.heart_rate import HeartRateArchive, HeartRateChunk
from app.routers.activities import ActivityBody

router = APIRouter(prefix="/api/v1/heart-rate", tags=["Heart rate"])
PAGE = 128
MAX_TIME = 253402300799999


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class Manifest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID
    started: int = Field(ge=0, le=MAX_TIME)
    ended: int = Field(ge=0, le=MAX_TIME)
    status: Literal["stopped", "interrupted", "error"]
    zone_id: str = Field(max_length=128)
    sample_count: int = Field(ge=0, le=2560000000)
    run_count: int = Field(ge=0, le=10000000)
    event_count: int = Field(ge=0, le=10000000)

    @field_validator("zone_id")
    @classmethod
    def valid_zone(cls, value):
        return ActivityBody.valid_zone(value)

    @model_validator(mode="after")
    def consistent(self):
        if not self.run_count <= self.sample_count <= self.run_count * 256:
            raise ValueError("Inconsistent sample/run counts")
        return self


class Run(BaseModel):
    model_config = ConfigDict(extra="forbid")
    bpm: int = Field(ge=1, le=65535)
    received: int = Field(ge=0, le=MAX_TIME)
    elapsed: int = Field(ge=0, le=9223372036854775807)
    last_received: int = Field(ge=0, le=MAX_TIME)
    last_elapsed: int = Field(ge=0, le=9223372036854775807)
    sample_count: int = Field(ge=1, le=256)
    timing: str = Field(max_length=6800)
    epoch: int = Field(ge=0, le=9223372036854775807)

    @model_validator(mode="after")
    def valid_timing(self):
        try:
            data = base64.b64decode(self.timing, validate=True)
        except (ValueError, binascii.Error):
            raise ValueError("Invalid timing encoding")
        offset, wall, elapsed = 0, self.received, self.elapsed

        def next_value():
            nonlocal offset
            value = 0
            for index in range(10):
                if offset >= len(data):
                    raise ValueError("Truncated timing")
                byte = data[offset]
                offset += 1
                if index == 9 and byte > 1:
                    raise ValueError("Invalid timing")
                value |= (byte & 127) << (7 * index)
                if not byte & 128:
                    return value
            raise ValueError("Invalid timing")

        for _ in range(self.sample_count - 1):
            delta = next_value()
            if delta > 15000:
                raise ValueError("Run crosses an elapsed-time gap")
            elapsed += delta
            signed = next_value()
            wall += (signed >> 1) ^ -(signed & 1)
            if not 0 <= wall <= MAX_TIME or elapsed > 9223372036854775807:
                raise ValueError("Timing out of range")
        if offset != len(data) or wall != self.last_received or elapsed != self.last_elapsed:
            raise ValueError("Timing/count mismatch")
        return self


class Event(BaseModel):
    model_config = ConfigDict(extra="forbid")
    at: int = Field(ge=0, le=MAX_TIME)
    state: str = Field(min_length=1, max_length=2000)


class Runs(BaseModel):
    model_config = ConfigDict(extra="forbid")
    records: list[Run] = Field(min_length=1, max_length=PAGE)


class Events(BaseModel):
    model_config = ConfigDict(extra="forbid")
    records: list[Event] = Field(min_length=1, max_length=PAGE)


def metadata(row):
    return {"id": row.id, "deleted": row.deleted, "complete": row.complete,
            "manifest": json.loads(row.manifest) if row.manifest else None}


async def owned(id, device, db, writing=False):
    row = await db.get(HeartRateArchive, (device.owner_id, str(id)), with_for_update=writing)
    if row is None:
        raise HTTPException(404, "HR capture not found")
    if writing and row.device_id != device.id:
        raise HTTPException(409, "Only the originating device may change this capture")
    if row.deleted:
        raise HTTPException(410, "HR capture was deleted")
    return row


async def commit(db):
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Concurrent HR change; retry the same request")


@router.put("/{id}")
async def begin(id: UUID, body: Manifest, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    if id != body.id:
        raise HTTPException(422, "Path and manifest IDs differ")
    payload = canonical(body.model_dump(mode="json"))
    row = await db.get(HeartRateArchive, (device.owner_id, str(id)), with_for_update=True)
    if row is None:
        row = HeartRateArchive(owner_id=device.owner_id, id=str(id), device_id=device.id, manifest=payload, complete=False, deleted=False)
        db.add(row)
    elif row.device_id != device.id:
        raise HTTPException(409, "Only the originating device may change this capture")
    elif row.deleted:
        raise HTTPException(410, "HR capture was deleted")
    elif row.manifest != payload:
        raise HTTPException(409, "HR manifest is immutable")
    await commit(db)
    return metadata(row)


async def write_chunk(id, kind, offset, records, device, db):
    row = await owned(id, device, db, writing=True)
    manifest = json.loads(row.manifest)
    total = manifest["run_count" if kind == "runs" else "event_count"]
    if offset < 0 or offset % PAGE or offset >= total or len(records) != min(PAGE, total - offset):
        raise HTTPException(422, "Unexpected chunk range")
    payload = canonical(records)
    old = await db.get(HeartRateChunk, (device.owner_id, str(id), kind, offset))
    if old is not None:
        if old.payload != payload:
            raise HTTPException(409, "HR chunk is immutable")
    elif row.complete:
        raise HTTPException(409, "HR capture is already complete")
    else:
        db.add(HeartRateChunk(owner_id=device.owner_id, session_id=str(id), kind=kind, offset=offset,
                             payload=payload, items=len(records), samples=sum(r["sample_count"] for r in records) if kind == "runs" else 0))
    await commit(db)
    return {"offset": offset, "records": records}


@router.put("/{id}/runs/{offset}")
async def runs(id: UUID, offset: int, body: Runs, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    return await write_chunk(id, "runs", offset, [r.model_dump() for r in body.records], device, db)


@router.put("/{id}/events/{offset}")
async def events(id: UUID, offset: int, body: Events, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    return await write_chunk(id, "events", offset, [r.model_dump() for r in body.records], device, db)


@router.post("/{id}/complete")
async def complete(id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await owned(id, device, db, writing=True)
    manifest = json.loads(row.manifest)
    for kind, expected in [("runs", manifest["run_count"]), ("events", manifest["event_count"])]:
        counts = (await db.execute(select(func.count(), func.coalesce(func.sum(HeartRateChunk.items), 0),
                                         func.coalesce(func.sum(HeartRateChunk.samples), 0)).where(
            HeartRateChunk.owner_id == device.owner_id, HeartRateChunk.session_id == str(id), HeartRateChunk.kind == kind))).one()
        if counts[0] != (expected + PAGE - 1) // PAGE or counts[1] != expected or (kind == "runs" and counts[2] != manifest["sample_count"]):
            raise HTTPException(409, "HR upload is incomplete")
    row.complete = True
    await commit(db)
    return metadata(row)


@router.get("/{id}")
async def read(id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await owned(id, device, db)
    if not row.complete:
        raise HTTPException(409, "HR upload is incomplete")
    return metadata(row)


@router.get("/{id}/{kind}/{offset}")
async def read_chunk(id: UUID, kind: Literal["runs", "events"], offset: int, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await owned(id, device, db)
    if not row.complete:
        raise HTTPException(409, "HR upload is incomplete")
    chunk = await db.get(HeartRateChunk, (device.owner_id, str(id), kind, offset))
    if chunk is None:
        raise HTTPException(404, "HR chunk not found")
    return {"offset": offset, "records": json.loads(chunk.payload)}


@router.delete("/{id}")
async def remove(id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await db.get(HeartRateArchive, (device.owner_id, str(id)), with_for_update=True)
    if row is None:
        row = HeartRateArchive(owner_id=device.owner_id, id=str(id), device_id=device.id, manifest=None, complete=False, deleted=True)
        db.add(row)
    elif row.device_id != device.id:
        raise HTTPException(409, "Only the originating device may delete this capture")
    row.deleted, row.complete, row.manifest = True, False, None
    await db.execute(delete(HeartRateChunk).where(HeartRateChunk.owner_id == device.owner_id, HeartRateChunk.session_id == str(id)))
    await commit(db)
    return {"id": str(id), "deleted": True}
