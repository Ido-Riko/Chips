import hashlib
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import get_db
from app.models.activity import Device
from app.config import settings

bearer = HTTPBearer(auto_error=False)


def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


async def require_device(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
    db: AsyncSession = Depends(get_db),
) -> Device:
    if request.url.scheme != "https" and not settings.allow_insecure_local:
        raise HTTPException(403, "HTTPS required")
    if not credentials or credentials.scheme.lower() != "bearer":
        raise HTTPException(401, "Device credential required", headers={"WWW-Authenticate": "Bearer"})
    device = await db.scalar(select(Device).where(
        Device.token_hash == token_digest(credentials.credentials), Device.revoked.is_(False)
    ).with_for_update())
    if device is None:
        raise HTTPException(401, "Invalid or revoked device", headers={"WWW-Authenticate": "Bearer"})
    return device
