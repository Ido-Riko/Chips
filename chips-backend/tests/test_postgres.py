"""Uses a disposable schema only; production DATABASE_URL is never used for these tests."""
import asyncio
import os
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import text
from sqlalchemy.pool import NullPool
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker
from app.auth import token_digest
from app.database import get_db
from app.models.activity import Device
from app.routers.activities import router


@pytest.mark.skipif(not os.environ.get("TEST_POSTGRES_URL"), reason="Disposable PostgreSQL not configured")
def test_migration_and_concurrent_sync():
    schema = "chips_test_" + uuid4().hex
    url = os.environ["TEST_POSTGRES_URL"]
    admin = create_async_engine(url, poolclass=NullPool)
    engine = create_async_engine(url, connect_args={"server_settings": {"search_path": schema}}, poolclass=NullPool)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    owner, device_id, record_id = str(uuid4()), str(uuid4()), str(uuid4())
    migration = "\n".join(path.read_text() for path in sorted(
        (Path(__file__).resolve().parents[1] / "migrations").glob("[0-9][0-9][0-9]_*.sql")))
    async def setup():
        async with admin.begin() as conn:
            await conn.execute(text(f'CREATE SCHEMA "{schema}"'))
        # Demonstrate upgrade of an existing voice table and preservation of its rows.
        async with engine.begin() as conn:
            await conn.execute(text("CREATE TABLE voice_logs (id SERIAL PRIMARY KEY, transcription TEXT)"))
            await conn.execute(text("INSERT INTO voice_logs(transcription) VALUES ('preserved')"))
            for _ in range(2):
                for sql in migration.split(';'):
                    if sql.strip(): await conn.execute(text(sql))
            assert await conn.scalar(text("SELECT transcription FROM voice_logs")) == "preserved"
        async with sessions() as db:
            db.add(Device(id=device_id, owner_id=owner, token_hash=token_digest("postgres-token"), revoked=False))
            await db.commit()
    async def cleanup():
        await engine.dispose()
        async with admin.begin() as conn:
            await conn.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
        await admin.dispose()
    asyncio.run(setup())
    app = FastAPI()
    app.include_router(router)
    async def database():
        async with sessions() as db: yield db
    app.dependency_overrides[get_db] = database
    auth = {"Authorization": "Bearer postgres-token"}
    body = dict(id=record_id, type="WORKOUT", occurred_at=1792443600000, zone_id="Asia/Jerusalem", local_day="2026-10-20", note="", deleted=False)
    path = f"/api/v1/activities/{record_id}"
    try:
        with TestClient(app, base_url="https://testserver") as client:
            def write(revision):
                return client.put(path, json=body | dict(revision=revision), headers=auth).status_code
            with ThreadPoolExecutor(max_workers=5) as pool:
                assert set(pool.map(write, [1, 3, 2, 5, 4, 5, 1])) == {200}
            records = client.get("/api/v1/activities", headers=auth).json()
            assert len(records) == 1 and records[0]["revision"] == 5
            assert client.delete(path, headers=auth).json()["deleted"]
            assert client.put(path, json=body | dict(revision=99), headers=auth).json()["deleted"]
    finally:
        asyncio.run(cleanup())
