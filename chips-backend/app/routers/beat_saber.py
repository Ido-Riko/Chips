import asyncio
import json
import time
from uuid import uuid4
import httpx
from fastapi import APIRouter, Depends, HTTPException, Path, Query
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from app.auth import require_device
from app.database import get_db
from app.models.activity import Device
from app.models.beat_saber import BeatSaberConnection, BeatSaverCache
from app.services import beat_saber as upstream

router = APIRouter(prefix="/api/v1/beat-saber", tags=["Beat Saber"])


class ConnectionBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    player_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,25}$")


def state(row):
    return {"player_id": row.player_id if row else None,
            "snapshot": json.loads(row.snapshot) if row and row.snapshot else None}


@router.get("")
async def read(device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    return state(await db.get(BeatSaberConnection, device.owner_id))


@router.put("/connection")
async def connect(body: ConnectionBody, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await db.get(BeatSaberConnection, device.owner_id, with_for_update=True)
    if row is None:
        row = BeatSaberConnection(owner_id=device.owner_id, player_id=body.player_id,
                                  generation=str(uuid4()), updated_at=0)
        db.add(row)
    elif row.player_id != body.player_id:
        row.player_id, row.generation, row.snapshot, row.updated_at = body.player_id, str(uuid4()), None, 0
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Connection changed; reload and retry.")
    return state(row)


@router.post("/refresh")
async def refresh(device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await db.get(BeatSaberConnection, device.owner_id)
    if row is None:
        raise HTTPException(409, "Save your BeatLeader player ID first.")
    if row.snapshot and int(time.time()) - row.updated_at < 60:
        return state(row)
    generation, player_id = row.generation, row.player_id
    try:
        snapshot = await asyncio.wait_for(upstream.fetch_snapshot(player_id), timeout=90)
    except upstream.UpstreamError as error:
        raise HTTPException(error.status, error.message)
    except (asyncio.TimeoutError, ValueError, KeyError, TypeError, OverflowError):
        raise HTTPException(502, "Source data was unavailable or invalid. Saved data is unchanged.")
    # CAS prevents an old response from restoring a removed/replaced connection.
    result = await db.execute(update(BeatSaberConnection).where(
        BeatSaberConnection.owner_id == device.owner_id, BeatSaberConnection.generation == generation
    ).values(snapshot=json.dumps(snapshot, allow_nan=False), updated_at=snapshot["fetched_at"]))
    if result.rowcount != 1:
        await db.rollback()
        raise HTTPException(409, "Connection changed while refreshing. Reload to continue.")
    await db.commit()
    return {"player_id": player_id, "snapshot": snapshot}


@router.delete("")
async def disconnect(device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    await db.execute(delete(BeatSaberConnection).where(BeatSaberConnection.owner_id == device.owner_id))
    await db.commit()
    return {"player_id": None, "snapshot": None}


@router.get("/maps/{map_hash}")
async def map_data(map_hash: str = Path(pattern=r"^[a-fA-F0-9]{40}$"),
                   mode: str = Query(min_length=1, max_length=80),
                   difficulty: str = Query(min_length=1, max_length=40),
                   device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    map_hash = map_hash.lower()
    cached = await db.get(BeatSaverCache, map_hash)
    try:
        if cached and int(time.time()) - cached.fetched_at < 86400:
            body = json.loads(cached.payload)
        else:
            async with httpx.AsyncClient(timeout=15, headers={"User-Agent": "CHIPS/0.1 personal-dashboard"}) as client:
                body = await upstream.get_json(client, f"{upstream.BEATSAVER}/maps/hash/{map_hash}")
            if not isinstance(body, dict):
                raise upstream.UpstreamError("Unexpected map response.")
            if cached:
                cached.payload, cached.fetched_at = json.dumps(body), int(time.time())
            else:
                db.add(BeatSaverCache(hash=map_hash, payload=json.dumps(body), fetched_at=int(time.time())))
            try:
                await db.commit()
            except IntegrityError:
                await db.rollback()  # Another request cached the same public map.
        return upstream.map_detail(body, map_hash, mode, difficulty)
    except upstream.UpstreamError as error:
        raise HTTPException(error.status, error.message)
    except (ValueError, KeyError, TypeError):
        raise HTTPException(502, "Invalid map data. The saved score is unchanged.")
