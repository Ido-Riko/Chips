import asyncio
from uuid import uuid4
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker
from app.database import Base, get_db
from app.auth import token_digest
from app.models.activity import Device, Activity
from app.routers.activities import router


@pytest.fixture
def api(tmp_path):
    engine = create_async_engine(f"sqlite+aiosqlite:///{tmp_path / 'activities.db'}")
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    owner, device_id = str(uuid4()), str(uuid4())
    async def setup():
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        async with sessions() as db:
            db.add_all([
                Device(id=device_id, owner_id=owner, token_hash=token_digest("owner-token"), revoked=False),
                Device(id=str(uuid4()), owner_id=str(uuid4()), token_hash=token_digest("stranger-token"), revoked=False),
                Device(id=str(uuid4()), owner_id=owner, token_hash=token_digest("other-device"), revoked=False),
                Device(id=str(uuid4()), owner_id=owner, token_hash=token_digest("revoked-token"), revoked=True),
            ])
            await db.commit()
    asyncio.run(setup())
    app = FastAPI()
    app.include_router(router)
    async def database():
        async with sessions() as db:
            yield db
    app.dependency_overrides[get_db] = database
    with TestClient(app, base_url="https://testserver") as client:
        yield client
    asyncio.run(engine.dispose())


def headers(token="owner-token"):
    return {"Authorization": f"Bearer {token}"}


def body(**kwargs):
    return dict(id=str(uuid4()), type="WORKOUT", occurred_at=1792443600000,
                zone_id="Asia/Jerusalem", local_day="2026-10-20", note="", revision=1, deleted=False) | kwargs


def test_retry_edit_delete_never_resurrects(api):
    record = body()
    path = f"/api/v1/activities/{record['id']}"
    for _ in range(3):
        assert api.put(path, json=record, headers=headers()).status_code == 200
    assert len(api.get("/api/v1/activities", headers=headers()).json()) == 1
    edited = record | dict(note="Session two", revision=2)
    assert api.put(path, json=edited, headers=headers()).json()["note"] == "Session two"
    assert api.put(path, json=record, headers=headers()).json()["revision"] == 2
    deleted = edited | dict(deleted=True, note="", revision=3)
    assert api.put(path, json=deleted, headers=headers()).json()["deleted"]
    assert api.put(path, json=edited, headers=headers()).json()["deleted"]
    assert api.put(path, json=edited | dict(revision=99), headers=headers()).json()["deleted"]
    assert api.get(path, headers=headers()).json()["deleted"]
    assert len(api.get("/api/v1/activities", headers=headers()).json()) == 1


def test_unauthorized_revoked_and_owner_isolation(api):
    record = body()
    path = f"/api/v1/activities/{record['id']}"
    for auth in ({}, headers("invalid"), headers("revoked-token")):
        assert api.get("/api/v1/activities", headers=auth).status_code == 401
        assert api.put(path, json=record, headers=auth).status_code == 401
        assert api.delete(path, headers=auth).status_code == 401
    assert api.put(path, json=record, headers=headers()).status_code == 200
    assert api.get("/api/v1/activities", headers=headers("stranger-token")).json() == []
    assert api.get(path, headers=headers("stranger-token")).status_code == 404
    assert api.delete(path, headers=headers("stranger-token")).status_code == 404
    assert api.put(path, json=record | dict(revision=2), headers=headers("other-device")).status_code == 409
    assert api.delete(path, headers=headers("other-device")).status_code == 409
    assert api.get("http://testserver/api/v1/activities", headers=headers()).status_code == 403


def test_out_of_order_offline_deletion_and_conflicting_revision(api):
    record = body(deleted=True, revision=2, note="discard on deletion")
    path = f"/api/v1/activities/{record['id']}"
    saved = api.put(path, json=record, headers=headers())
    assert saved.status_code == 200 and saved.json()["note"] == ""
    assert api.put(path, json=record | dict(deleted=False, revision=1), headers=headers()).json()["deleted"]
    active = body()
    path = f"/api/v1/activities/{active['id']}"
    assert api.put(path, json=active, headers=headers()).status_code == 200
    assert api.put(path, json=active | dict(note="conflict"), headers=headers()).status_code == 409
    assert api.delete(path, headers=headers()).json()["deleted"]
    assert api.delete(path, headers=headers()).json()["revision"] == 2


@pytest.mark.parametrize("change", [dict(type="FAKE"), dict(local_day="2026-02-30"), dict(zone_id="invalid"), dict(revision=0), dict(note="x" * 2001)])
def test_validation(api, change):
    record = body(**change)
    assert api.put(f"/api/v1/activities/{record['id']}", json=record, headers=headers()).status_code == 422
