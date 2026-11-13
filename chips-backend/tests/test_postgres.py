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
from app.routers.drawings import router as drawing_router
from app.routers.pantry import router as pantry_router
from app.routers.heart_rate import router as hr_router


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
    app.include_router(drawing_router)
    app.include_router(pantry_router)
    app.include_router(hr_router)
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
        # The same additive migration must store/retrieve actual JPEG BYTEA, not just metadata.
        from test_drawings import jpeg
        drawing_id = str(uuid4())
        drawing_path = f"/api/v1/drawings/{drawing_id}"
        with TestClient(app, base_url="https://testserver") as drawing_client:
            result = drawing_client.put(drawing_path, headers=auth,
                files={"image": ("drawing.jpg", jpeg(), "image/jpeg")},
                data={"goal": "Shading", "created_at": "1794500000000"})
            assert result.status_code == 200, result.text
            assert drawing_client.get(drawing_path + "/image", headers=auth).content == jpeg()
            assert drawing_client.delete(drawing_path, headers=auth).json()["deleted"]
            assert drawing_client.get(drawing_path + "/image", headers=auth).status_code == 404
        from test_pantry import movement
        tx = movement()
        with TestClient(app, base_url="https://testserver") as pantry_client:
            def purchase(_):
                return pantry_client.put(f"/api/v1/pantry/transactions/{tx['id']}", json=tx, headers=auth).status_code
            with ThreadPoolExecutor(max_workers=4) as pool:
                assert set(pool.map(purchase, range(4))) == {200}
            stock = pantry_client.get("/api/v1/pantry/inventory", headers=auth).json()
            assert len(stock) == 1 and stock[0]["quantity"] == "2"
        # Check the actual PostgreSQL HR schema, same-ID retries and delete/upload races.
        from test_heart_rate import manifest, run
        hr_manifest = manifest()
        hr_path = f"/api/v1/heart-rate/{hr_manifest['id']}"
        with TestClient(app, base_url="https://testserver") as hr_client:
            assert hr_client.put(hr_path, json=hr_manifest, headers=auth).status_code == 200
            def hr_run(_):
                return hr_client.put(hr_path + "/runs/0", json={"records": [run()]}, headers=auth).status_code
            with ThreadPoolExecutor(max_workers=4) as pool:
                assert set(pool.map(hr_run, range(4))) == {200}
            assert hr_client.put(hr_path + "/events/0", json={"records": [dict(at=4000, state="disconnected")]}, headers=auth).status_code == 200
            assert hr_client.post(hr_path + "/complete", headers=auth).status_code == 200
            linked = body | dict(id=str(uuid4()), revision=1, type="BEAT_SABER", hr_session_id=hr_manifest["id"])
            linked_path = f"/api/v1/activities/{linked['id']}"
            assert hr_client.put(linked_path, json=linked, headers=auth).status_code == 200
            assert hr_client.get(hr_path + "/runs/0", headers=auth).json()["records"] == [run()]
            def race(index):
                if index % 2:
                    return hr_client.delete(hr_path, headers=auth).status_code
                return hr_run(index)
            with ThreadPoolExecutor(max_workers=4) as pool:
                assert set(pool.map(race, range(8))) <= {200, 410}
            assert hr_client.get(hr_path, headers=auth).status_code == 410
            assert not hr_client.get(linked_path, headers=auth).json()["deleted"]
            async def erased():
                async with engine.connect() as conn:
                    assert await conn.scalar(text("SELECT COUNT(*) FROM heart_rate_chunks WHERE session_id=:id"), {"id": hr_manifest["id"]}) == 0
            asyncio.run(erased())
    finally:
        asyncio.run(cleanup())
