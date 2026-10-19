import pytest
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, patch
from app.main import app
from app.database import get_db

client = TestClient(app)

# Mocked DB session for testing
async def override_get_db():
    yield AsyncMock()

app.dependency_overrides[get_db] = override_get_db

def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}

@patch("app.routers.voice.stt_service.transcribe_audio", new_callable=AsyncMock)
@patch("app.routers.voice.llm_service.get_response", new_callable=AsyncMock)
def test_process_voice_log(mock_claude, mock_stt):
    # Setup mocks
    mock_stt.return_value = "This is a test transcription"
    mock_claude.return_value = "This is Claude's response"

    # Create dummy audio file content
    files = {"audio": ("test.m4a", b"dummy audio content", "audio/mp4")}

    response = client.post("/api/v1/voice-log", files=files)
    
    # Check outcomes
    assert response.status_code == 200
    assert response.text == "This is Claude's response"
    
    mock_stt.assert_called_once()
    mock_claude.assert_called_once()
