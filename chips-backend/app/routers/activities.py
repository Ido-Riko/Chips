from datetime import date
from typing import Literal
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from app.auth import require_device
from app.database import get_db
from app.models.activity import Activity, Device

router = APIRouter(prefix="/api/v1")


class ActivityBody(BaseModel):
    model_config = ConfigDict(from_attributes=True, extra="forbid")
    id: UUID
    type: Literal["WORKOUT", "BEAT_SABER", "DRAWING", "JAPANESE"]
    occurred_at: int = Field(ge=0, le=253402300799000)
    zone_id: str = Field(max_length=128)
    local_day: str
    note: str = Field(default="", max_length=2000)
    revision: int = Field(ge=1, le=9223372036854775807)
    deleted: bool = False

    @field_validator("zone_id")
    @classmethod
    def valid_zone(cls, value):
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError("Unknown timezone")
        return value

    @field_validator("local_day")
    @classmethod
    def valid_day(cls, value):
        if date.fromisoformat(value).isoformat() != value:
            raise ValueError("Expected YYYY-MM-DD")
        return value


def payload(body: ActivityBody):
    values = body.model_dump(mode="json")
    if body.deleted:
        values["note"] = ""
    return values


@router.get("/device")
async def identity(device: Device = Depends(require_device)):
    return {"owner_id": device.owner_id, "device_id": device.id}


@router.get("/activities", response_model=list[ActivityBody])
async def read_activities(device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    # Personal deployment: full snapshot includes tombstones, so missed deletes are recoverable.
    return list((await db.scalars(select(Activity).where(Activity.owner_id == device.owner_id)
                                .order_by(Activity.occurred_at, Activity.id))).all())


@router.get("/activities/{record_id}", response_model=ActivityBody)
async def read_activity(record_id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    record = await db.get(Activity, (device.owner_id, str(record_id)))
    if record is None:
        raise HTTPException(404, "Activity not found")
    return record


@router.put("/activities/{record_id}", response_model=ActivityBody)
async def put_activity(record_id: UUID, body: ActivityBody, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    if record_id != body.id:
        raise HTTPException(422, "Path and body IDs differ")
    record = await db.get(Activity, (device.owner_id, str(record_id)), with_for_update=True)
    if record is None:
        record = Activity(owner_id=device.owner_id, device_id=device.id, **payload(body))
        db.add(record)
    else:
        if record.device_id != device.id:
            raise HTTPException(409, "Only the originating device may modify this record")
        # Deletion is terminal. Out-of-order retries return the authoritative row.
        if not record.deleted and body.revision > record.revision:
            for key, value in payload(body).items():
                setattr(record, key, value)
        elif not record.deleted and body.revision == record.revision and payload(body) != payload(ActivityBody.model_validate(record)):
            raise HTTPException(409, "A revision cannot contain different content")
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Concurrent creation; retry with the originating device")
    return record


@router.delete("/activities/{record_id}", response_model=ActivityBody)
async def delete_activity(record_id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    record = await db.get(Activity, (device.owner_id, str(record_id)), with_for_update=True)
    if record is None:
        raise HTTPException(404, "Activity not found; offline deletes use PUT with a tombstone")
    if record.device_id != device.id:
        raise HTTPException(409, "Only the originating device may delete this record")
    if not record.deleted:
        record.deleted = True
        record.note = ""
        record.revision += 1
        await db.commit()
    return record
