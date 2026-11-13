from uuid import uuid4
import json
import pytest
from test_activities import api, headers
from app.routers.pantry import router
from app.models.daily_log import VoiceLog
from app.services import drawing as vision
from test_drawings import jpeg


@pytest.fixture
def pantry_api(api):
    api.app.include_router(router)
    return api


def movement(**changes):
    return dict(id=str(uuid4()), created_at=1794500000000, source="barcode", fingerprint="", lines=[dict(
        key=str(uuid4()), name="Milk", barcode="7290000000000", unit="item", package="1 L", expiry="", delta="2", low="1")]) | changes


def put(client, tx, token="owner-token"):
    return client.put(f"/api/v1/pantry/transactions/{tx['id']}", json=tx, headers=headers(token))


def test_retry_restore_consume_correction_and_owner_isolation(pantry_api):
    c = pantry_api; tx = movement()
    for _ in range(3):
        r = put(c, tx); assert r.status_code == 200, r.text
    assert len(c.get("/api/v1/pantry/transactions", headers=headers()).json()) == 1
    assert c.get("/api/v1/pantry/inventory", headers=headers()).json()[0]["quantity"] == "2"
    consume = movement(source="consume", lines=[tx["lines"][0] | {"delta": "-1"}])
    for _ in range(2): assert put(c, consume).status_code == 200
    item = c.get("/api/v1/pantry/inventory", headers=headers("other-device")).json()[0]
    assert item["quantity"] == "1" and item["low_stock"]
    assert item["package"] == "1 L" and item["unit"] == "item"
    assert c.get("/api/v1/pantry/inventory", headers=headers("stranger-token")).json() == []
    assert c.get("/api/v1/pantry/transactions", headers=headers("stranger-token")).json() == []
    correction = movement(source="correction", lines=[tx["lines"][0] | {"delta": "-1"}])
    assert put(c, correction).status_code == 200
    assert c.get("/api/v1/pantry/inventory", headers=headers()).json() == []
    assert put(c, tx | {"source": "manual"}).status_code == 409
    assert put(c, movement(), "revoked-token").status_code == 401
    assert c.get("/api/v1/pantry/inventory").status_code == 401
    assert c.get("/api/v1/pantry/transactions?after=bad", headers=headers()).status_code == 422
    page = c.get("/api/v1/pantry/transactions", headers=headers()).json()
    first = page[0]["id"]
    assert all(t["id"] > first for t in c.get(f"/api/v1/pantry/transactions?after={first}", headers=headers()).json())


@pytest.mark.parametrize("change", [{"delta": "NaN"}, {"delta": "Infinity"}, {"unit": "price"}, {"delta": "1000001"}, {"expiry": "2026-02-30"}, {"name": "  "}, {"barcode": "abc"}])
def test_bad_quantities_and_metadata_rejected(pantry_api, change):
    tx = movement(); tx["lines"][0].update(change)
    assert put(pantry_api, tx).status_code == 422


def test_receipt_is_draft_only_and_inventory_unchanged(pantry_api, monkeypatch):
    async def analyze(image, goal, system_prompt=None):
        assert image == jpeg() and "prices" in system_prompt
        return "חלב | 2 | item"
    monkeypatch.setattr(vision, "analyze", analyze)
    r = pantry_api.post("/api/v1/pantry/receipt", headers=headers(), files={"image": ("receipt.jpg", jpeg(), "image/jpeg")})
    assert r.status_code == 200 and r.json()["draft"] == "חלב | 2 | item"
    assert pantry_api.get("/api/v1/pantry/inventory", headers=headers()).json() == []
    assert pantry_api.post("/api/v1/pantry/receipt", headers=headers(), files={"image": ("bad.jpg", b"not jpeg")}).status_code == 422


def test_main_assistant_receives_only_owners_inventory(pantry_api, monkeypatch):
    from app.routers.voice import router as voice_router
    import app.services.stt as stt
    import app.services.llm as llm
    c = pantry_api; c.app.include_router(voice_router)
    assert put(c, movement()).status_code == 200
    assert put(c, movement(lines=[movement()["lines"][0] | {"name": "Private other owner"}]), "stranger-token").status_code == 200
    async def transcribe(_): return "What can I eat?"
    async def respond(message, prompt):
        assert "Milk" in prompt and "Private other owner" not in prompt
        assert "Never change stock automatically" in prompt
        return "Use your milk."
    monkeypatch.setattr(stt, "transcribe_audio", transcribe)
    monkeypatch.setattr(llm, "get_response", respond)
    response = c.post("/api/v1/voice-log", headers=headers(), files={"audio": ("audio.aac", b"test audio")})
    assert response.status_code == 200, response.text
