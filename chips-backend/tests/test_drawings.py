import base64
import io
from uuid import uuid4
import httpx
import pytest
from PIL import Image
from test_activities import api, headers
from app.routers.drawings import router
from app.services import drawing as vision


def jpeg():
    image = Image.new("RGB", (64, 48), "white")
    output = io.BytesIO()
    image.save(output, "JPEG")
    return output.getvalue()


@pytest.fixture
def drawings_api(api):
    api.app.include_router(router)
    return api


def upload(client, id, data=None, goal="Shading", auth=None):
    return client.put(f"/api/v1/drawings/{id}", headers=auth or headers(),
                      files={"image": ("drawing.jpg", data if data is not None else jpeg(), "image/jpeg")}, data={"goal": goal, "created_at": "1794500000000"})


def test_upload_is_durable_idempotent_and_owner_scoped(drawings_api):
    client = drawings_api
    id = str(uuid4())
    for _ in range(2):
        result = upload(client, id)
        assert result.status_code == 200, result.text
        assert result.json()["goal"] == "Shading"
        assert result.json()["created_at"] == 1794500000000
    assert len(client.get("/api/v1/drawings", headers=headers()).json()) == 1
    assert client.get(f"/api/v1/drawings/{id}/image", headers=headers()).content == jpeg()
    assert client.get(f"/api/v1/drawings/{id}", headers=headers("stranger-token")).status_code == 404
    assert client.get("/api/v1/drawings", headers=headers("stranger-token")).json() == []
    assert client.post(f"/api/v1/drawings/{id}/analyze", headers=headers("stranger-token")).status_code == 404
    assert client.get(f"/api/v1/drawings/{id}").status_code == 401
    assert client.get(f"/api/v1/drawings/{id}", headers=headers("revoked-token")).status_code == 401
    assert upload(client, id, goal="Different goal").status_code == 409
    # Another enrolled device belonging to the same owner can access the drawing.
    assert client.get(f"/api/v1/drawings/{id}", headers=headers("other-device")).status_code == 200


def test_delete_scrubs_media_feedback_and_blocks_delayed_upload(drawings_api, monkeypatch):
    client = drawings_api
    id = str(uuid4())
    upload(client, id)
    async def analyze(*_): return "Strong shapes. Practice a sphere for 10 minutes."
    monkeypatch.setattr(vision, "analyze", analyze)
    assert client.post(f"/api/v1/drawings/{id}/analyze", headers=headers()).status_code == 200
    for _ in range(2): assert client.delete(f"/api/v1/drawings/{id}", headers=headers()).status_code == 200
    assert client.get(f"/api/v1/drawings/{id}/image", headers=headers()).status_code == 404
    assert upload(client, id).status_code == 410
    pending = str(uuid4())
    client.delete(f"/api/v1/drawings/{pending}", headers=headers())
    assert upload(client, pending).status_code == 410
    assert client.get("/api/v1/drawings", headers=headers()).json() == []


def test_invalid_media_and_missing_vision_keep_saved_drawing(drawings_api, monkeypatch):
    client = drawings_api
    id = str(uuid4())
    assert upload(client, id, b"not an image").status_code == 422
    assert upload(client, id, b"x" * (vision.MAX_BYTES + 1)).status_code == 413
    assert upload(client, id).status_code == 200
    monkeypatch.setattr(vision.settings, "drawing_vision_url", None)
    assert client.post(f"/api/v1/drawings/{id}/analyze", headers=headers()).status_code == 503
    assert client.get(f"/api/v1/drawings/{id}/image", headers=headers()).content == jpeg()
    assert client.get(f"/api/v1/drawings/{id}", headers=headers()).json()["feedback"] is None


def test_configured_vision_sends_image_and_caches_feedback(drawings_api, monkeypatch):
    client = drawings_api
    id = str(uuid4())
    upload(client, id)
    calls = []
    async def handle(request):
        import json
        body = json.loads(request.content)
        calls.append(body)
        image = body["messages"][1]["content"][1]["image_url"]["url"]
        assert base64.b64decode(image.split(",")[1]) == jpeg()
        assert request.headers["Authorization"] == "Bearer model-key"
        return httpx.Response(200, json={"choices": [{"message": {"content": "Try five shaded spheres."}}]})
    original = httpx.AsyncClient
    monkeypatch.setattr(vision.httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(handle), **kwargs))
    monkeypatch.setattr(vision.settings, "drawing_vision_url", "http://127.0.0.1:11434/v1/chat/completions")
    monkeypatch.setattr(vision.settings, "drawing_vision_model", "test-vision")
    monkeypatch.setattr(vision.settings, "drawing_vision_api_key", "model-key")
    for _ in range(2):
        response = client.post(f"/api/v1/drawings/{id}/analyze", headers=headers())
        assert response.status_code == 200, response.text
        assert response.json()["feedback"] == "Try five shaded spheres."
        assert response.json()["model"] == "test-vision"
    assert len(calls) == 1


def test_failed_provider_retains_image_and_has_no_fake_feedback(drawings_api, monkeypatch):
    client = drawings_api
    id = str(uuid4()); upload(client,id)
    original = httpx.AsyncClient
    monkeypatch.setattr(vision.httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(lambda request: httpx.Response(500)), **kwargs))
    monkeypatch.setattr(vision.settings, "drawing_vision_url", "http://127.0.0.1:11434/v1/chat/completions")
    monkeypatch.setattr(vision.settings, "drawing_vision_model", "test-vision")
    assert client.post(f"/api/v1/drawings/{id}/analyze", headers=headers()).status_code == 502
    assert client.get(f"/api/v1/drawings/{id}/image", headers=headers()).content == jpeg()
