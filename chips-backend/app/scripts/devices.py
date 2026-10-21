"""Operator-only enrollment and revocation; tokens are shown exactly once."""
import argparse
import asyncio
import secrets
from uuid import UUID, uuid4
from sqlalchemy import select
from app.auth import token_digest
from app.database import async_session_maker
from app.models.activity import Device


async def run(args):
    async with async_session_maker() as db:
        if args.action == "revoke":
            device = await db.get(Device, str(UUID(args.device)))
            if device is None:
                raise SystemExit("Unknown device")
            device.revoked = True
            await db.commit()
            print("Device revoked")
        else:
            owner = str(UUID(args.owner))
            device_id = str(UUID(args.device)) if args.device else str(uuid4())
            device = await db.get(Device, device_id)
            if device is not None and device.owner_id != owner:
                raise SystemExit("Device belongs to a different owner")
            token = secrets.token_urlsafe(48)
            if device is None:
                device = Device(id=device_id, owner_id=owner)
                db.add(device)
            device.token_hash = token_digest(token)
            device.revoked = False
            await db.commit()
            print(f"Owner: {owner}\nDevice: {device_id}\nToken (store securely): {token}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["enroll", "revoke"])
    parser.add_argument("--owner", help="Stable owner UUID (required for enrollment)")
    parser.add_argument("--device", help="Existing device UUID for rotation/revocation")
    args = parser.parse_args()
    if args.action == "enroll" and not args.owner or args.action == "revoke" and not args.device:
        parser.error("enroll requires --owner; revoke requires --device")
    asyncio.run(run(args))
