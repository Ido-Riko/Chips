import asyncio
import httpx
import pytest
from test_activities import api, headers
from app.routers.beat_saber import router
from app.services import beat_saber as source

HASH = "a" * 40


def graph(**kwargs):
    return dict(acc=0.93, leaderboardId="board91", songName="Song", hash=HASH,
                mode="Standard", diff="ExpertPlus", timeset=1794500000,
                stars=8, pp=250, techRating=4, passRating=6, accRating=7, modifiers="") | kwargs


def score(score_id=1):
    return dict(id=score_id, accuracy=0.93, leaderboardId="board91", timepost=1794500000,
        pp=250, modifiers="", badCuts=2, missedNotes=3, fullCombo=False, accLeft=107, accRight=109,
        leaderboard=dict(song=dict(name="Song", hash=HASH, bpm=150),
            difficulty=dict(modeName="Standard", difficultyName="ExpertPlus", stars=8)),
        scoreImprovement=dict(accuracy=0.01, pp=5))


@pytest.fixture
def beat_api(api):
    api.app.include_router(router)
    return api


@pytest.fixture
def upstream(monkeypatch):
    calls = []
    async def get_json(client, url, params=None):
        calls.append((url, params))
        if url.endswith("/accgraph"): return [graph(), graph()]
        if url.endswith("/history"): return [dict(timestamp=1794500000, pp=100, rank=20)]
        if url.endswith("/scores"): return dict(metadata=dict(total=1), data=[score()])
        if "/maps/hash/" in url:
            return dict(id="map1", metadata=dict(bpm=150, duration=120), tags=["tech"], versions=[
                dict(hash="b" * 40, diffs=[dict(characteristic="Standard", difficulty="ExpertPlus", nps=999)]),
                dict(hash=HASH.upper(), diffs=[dict(characteristic="Standard", difficulty="ExpertPlus", nps=5, njs=18, notes=400)])])
        return dict(id="player1", name="Player", rank=10, pp=1000, country="IL", countryRank=1)
    monkeypatch.setattr(source, "get_json", get_json)
    return calls


def test_connection_refresh_retries_owner_isolation_and_removal(beat_api, upstream):
    client = beat_api
    assert client.post("/api/v1/beat-saber/refresh", headers=headers()).status_code == 409
    for auth in ({}, headers("revoked-token")):
        assert client.get("/api/v1/beat-saber", headers=auth).status_code == 401
    assert client.put("/api/v1/beat-saber/connection", headers=headers(), json=dict(player_id="player1")).status_code == 200
    first = client.post("/api/v1/beat-saber/refresh", headers=headers())
    assert first.status_code == 200, first.text
    snap = first.json()["snapshot"]
    assert len(snap["graph"]) == 1 and snap["scores"][0]["accuracy"] == 93
    assert snap["scores"][0]["left_cut"] == 107
    assert snap["scores"][0]["accuracy_gain"] == 1
    count = len(upstream)
    assert client.post("/api/v1/beat-saber/refresh", headers=headers()).json() == first.json()
    assert len(upstream) == count  # Cooldown avoids hammering providers.
    assert client.get("/api/v1/beat-saber", headers=headers("other-device")).json() == first.json()
    assert client.get("/api/v1/beat-saber", headers=headers("stranger-token")).json()["snapshot"] is None
    assert client.delete("/api/v1/beat-saber", headers=headers()).status_code == 200
    assert client.get("/api/v1/beat-saber", headers=headers()).json()["player_id"] is None
    assert client.post("/api/v1/beat-saber/refresh", headers=headers()).status_code == 409


def test_failed_refresh_keeps_snapshot_and_changing_player_clears_it(beat_api, upstream, monkeypatch):
    client = beat_api
    client.put("/api/v1/beat-saber/connection", headers=headers(), json=dict(player_id="player1"))
    saved = client.post("/api/v1/beat-saber/refresh", headers=headers()).json()
    monkeypatch.setattr("app.routers.beat_saber.time.time", lambda: saved["snapshot"]["fetched_at"] + 61)
    async def fail(_): raise source.UpstreamError("Source is busy", 503)
    monkeypatch.setattr(source, "fetch_snapshot", fail)
    assert client.post("/api/v1/beat-saber/refresh", headers=headers()).status_code == 503
    assert client.get("/api/v1/beat-saber", headers=headers()).json() == saved
    changed = client.put("/api/v1/beat-saber/connection", headers=headers(), json=dict(player_id="player2")).json()
    assert changed == dict(player_id="player2", snapshot=None)
    assert client.put("/api/v1/beat-saber/connection", headers=headers(), json=dict(player_id="../../secret")).status_code == 422


def test_exact_map_hash_difficulty_cache_and_authentication(beat_api, upstream):
    path = f"/api/v1/beat-saber/maps/{HASH}?mode=Standard&difficulty=ExpertPlus"
    assert beat_api.get(path).status_code == 401
    response = beat_api.get(path, headers=headers())
    assert response.status_code == 200, response.text
    assert response.json()["nps"] == 5  # Not the newest/first version's 999.
    assert beat_api.get(path, headers=headers()).json() == response.json()
    assert len(upstream) == 1
    assert beat_api.get(path.replace("ExpertPlus", "Easy"), headers=headers()).status_code == 404
    assert beat_api.get(path.replace(HASH, "bad"), headers=headers()).status_code == 422


def test_pagination_is_bounded_and_scores_deduplicate(upstream, monkeypatch):
    old = source.get_json
    async def pages(client, url, params=None):
        if url.endswith("/scores"):
            return dict(metadata=dict(total=500), data=[score(params["page"]), score(params["page"])])
        return await old(client, url, params)
    monkeypatch.setattr(source, "get_json", pages)
    data = asyncio.run(source.fetch_snapshot("player1"))
    assert len(data["scores"]) == 3
    assert data["total_scores"] == 500
    assert "not every attempt" in data["coverage"]


def test_refresh_cannot_restore_a_connection_removed_while_fetching(beat_api, upstream, monkeypatch):
    beat_api.put("/api/v1/beat-saber/connection", headers=headers(), json=dict(player_id="player1"))
    snapshot = asyncio.run(source.fetch_snapshot("player1"))
    async def delayed(_):
        response = await asyncio.to_thread(lambda: beat_api.delete("/api/v1/beat-saber", headers=headers("other-device")))
        assert response.status_code == 200
        return snapshot
    monkeypatch.setattr(source, "fetch_snapshot", delayed)
    response = beat_api.post("/api/v1/beat-saber/refresh", headers=headers())
    assert response.status_code == 409
    assert beat_api.get("/api/v1/beat-saber", headers=headers()).json()["snapshot"] is None


def test_invalid_graph_is_rejected_instead_of_saving_partial_data(upstream, monkeypatch):
    old = source.get_json
    async def bad_graph(client, url, params=None):
        if url.endswith("/accgraph"): return [graph(), graph(acc=float("nan"))]
        return await old(client, url, params)
    monkeypatch.setattr(source, "get_json", bad_graph)
    with pytest.raises(ValueError): asyncio.run(source.fetch_snapshot("player1"))


def test_modifier_score_does_not_claim_base_ratings_are_adjusted():
    value = score()
    value["modifiers"] = "FS"
    assert source.recent_score(value)["stars"] is None


def test_upstream_429_retry_no_credentials_and_no_redirect_following(monkeypatch):
    seen = []
    def handle(request):
        seen.append(request)
        return httpx.Response(429 if len(seen) == 1 else 200, json={"ok": True})
    async def go():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            return await source.get_json(client, source.BEATLEADER + "/player/test")
    assert asyncio.run(go()) == {"ok": True}
    assert len(seen) == 2 and all("authorization" not in r.headers for r in seen)
    async def redirect():
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(302, headers={"location": "https://other.invalid/"}))) as client:
            return await source.get_json(client, source.BEATLEADER + "/test")
    with pytest.raises(source.UpstreamError): asyncio.run(redirect())
