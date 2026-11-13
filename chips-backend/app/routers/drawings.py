import hashlib
import time
from uuid import UUID
from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from app.auth import require_device
from app.database import get_db
from app.models.activity import Device
from app.models.drawing import Drawing
from app.services import drawing as vision

router = APIRouter(prefix="/api/v1/drawings", tags=["Drawings"])


def metadata(row):
    return dict(id=row.id, sha256=row.sha256, goal=row.goal, created_at=row.created_at,
                feedback=row.feedback, analyzed_at=row.analyzed_at, model=row.model, deleted=bool(row.deleted))


async def owned(id, device, db):
    row = await db.get(Drawing, (device.owner_id, str(id)), with_for_update=True)
    if row is None or row.deleted:
        raise HTTPException(404, "Drawing not found")
    return row


@router.get("")
async def history(device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    rows = (await db.scalars(select(Drawing).where(Drawing.owner_id == device.owner_id, Drawing.deleted == 0)
                            .order_by(Drawing.created_at.desc()).limit(200))).all()
    return [metadata(row) for row in rows]


@router.put("/{id}")
async def upload(id: UUID, image: UploadFile = File(...), goal: str = Form("", max_length=2000),
                 created_at: int = Form(..., gt=0, le=253402300799999),
                 device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    data = await image.read(vision.MAX_BYTES + 1)
    await image.close()
    vision.validate_image(data)
    digest = hashlib.sha256(data).hexdigest()
    row = await db.get(Drawing, (device.owner_id, str(id)), with_for_update=True)
    if row:
        if row.deleted:
            raise HTTPException(410, "Drawing was deleted")
        if row.sha256 != digest or row.goal != goal or row.created_at != created_at:
            raise HTTPException(409, "Drawing ID already has different content")
    else:
        row = Drawing(owner_id=device.owner_id, id=str(id), sha256=digest, image=data,
                      goal=goal, created_at=created_at, deleted=0)
        db.add(row)
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Drawing changed concurrently; retry the same request.")
    return metadata(row)


@router.get("/{id}")
async def read(id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    return metadata(await owned(id, device, db))


@router.get("/{id}/image")
async def image(id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await owned(id, device, db)
    return Response(row.image, media_type="image/jpeg", headers={"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"})


@router.post("/{id}/analyze")
async def analyze(id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await owned(id, device, db)
    if row.feedback is None:
        row.feedback = await vision.analyze(row.image, row.goal)
        row.analyzed_at = int(time.time() * 1000)
        row.model = vision.settings.drawing_vision_model
        await db.commit()
    return metadata(row)


@router.delete("/{id}")
async def remove(id: UUID, device: Device = Depends(require_device), db: AsyncSession = Depends(get_db)):
    row = await db.get(Drawing, (device.owner_id, str(id)), with_for_update=True)
    if row is None:
        # A tombstone also blocks a delayed upload of a drawing deleted while offline.
        row = Drawing(owner_id=device.owner_id, id=str(id), sha256="", goal="", created_at=0, deleted=1)
        db.add(row)
    row.deleted, row.image, row.feedback, row.goal, row.model, row.analyzed_at = 1, None, None, "", None, None
    try:
        await db.commit()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "Drawing changed concurrently; retry deletion.")
    return {"id": str(id), "deleted": True}
