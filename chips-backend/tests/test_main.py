import pytest
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, Mock, patch
from app.auth import require_device
from app.models.activity import Device
from app.main import app
from app.database import get_db

client = TestClient(app)

# Mocked DB session for testing
async def override_get_db():
    db = AsyncMock()
    db.add = Mock()
    db.scalars.return_value = Mock(all=Mock(return_value=[]))
    yield db

@pytest.fixture(autouse=True)
def mock_database():
    app.dependency_overrides[get_db] = override_get_db
    app.dependency_overrides[require_device] = lambda: Device(id="test-device", owner_id="test-owner")
    yield
    app.dependency_overrides.clear()

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

def test_legacy_voice_requires_versioned_identity():
    response = client.post("/api/v1/voice-log", files={"audio": ("test.m4a", b"audio", "audio/mp4")})
    assert response.status_code == 422


def test_voice_capabilities():
    assert client.get("/api/v1/voice-capabilities").json() == {"protocol_version": 1, "idempotency": True}


def test_voice_rejects_missing_device_credentials():
    app.dependency_overrides.pop(require_device)
    response = client.post("https://testserver/api/v1/voice-log", files={"audio": ("test.m4a", b"audio", "audio/mp4")})
    assert response.status_code == 401
