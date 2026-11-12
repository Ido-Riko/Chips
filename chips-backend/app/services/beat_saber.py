"""Read-only public upstream adapters. Never forward the CHIPS device credential."""
import asyncio
import json
import math
import re
import time
import httpx

BEATLEADER = "https://api.beatleader.com"
BEATSAVER = "https://api.beatsaver.com"
MAX_RESPONSE = 12 * 1024 * 1024


class UpstreamError(Exception):
    def __init__(self, message: str, status: int = 502):
        self.message, self.status = message, status
        super().__init__(message)


async def get_json(client: httpx.AsyncClient, url: str, params=None):
    # Fixed upstream origins at call sites; redirect targets are deliberately not followed.
    for attempt in range(2):
        try:
            async with client.stream("GET", url, params=params) as response:
                if response.status_code == 429 or response.status_code >= 500:
                    if attempt == 0:
                        await asyncio.sleep(0.5)
                        continue
                    raise UpstreamError("Source is busy. Saved data is unchanged; retry later.", 503)
                if response.status_code == 404:
                    raise UpstreamError("Player or map was not found at the source.", 404)
                if response.status_code != 200:
                    raise UpstreamError("Source could not provide this data.")
                data = bytearray()
                async for chunk in response.aiter_bytes():
                    data.extend(chunk)
                    if len(data) > MAX_RESPONSE:
                        raise UpstreamError("Source response is too large.")
                return json.loads(data)
        except (httpx.HTTPError, ValueError) as error:
            raise UpstreamError("Could not read source data. Saved data is unchanged.") from error


def number(value, default=None):
    if isinstance(value, bool) or value is None:
        return default
    try:
        result = float(value)
        return result if math.isfinite(result) else default
    except (TypeError, ValueError):
        return default


def text(value, limit=300):
    return value[:limit] if isinstance(value, str) else ""


def epoch(value):
    value = number(value)
    if value is None or not 0 < value <= 253402300799:
        raise ValueError("Invalid source timestamp")
    return int(value)


def graph_point(row):
    accuracy = number(row.get("acc"))
    identity = text(row.get("leaderboardId"), 80)
    if not identity or accuracy is None or not 0 <= accuracy <= 1:
        raise ValueError("Invalid graph point")
    return dict(key=identity, score_id=None, leaderboard_id=identity,
                song_name=text(row.get("songName")), hash=text(row.get("hash"), 40).lower(),
                mode=text(row.get("mode"), 80), difficulty=text(row.get("diff"), 40),
                accuracy=accuracy * 100, timestamp=epoch(row.get("timeset")),
                stars=number(row.get("stars")), pp=number(row.get("pp")),
                pass_rating=number(row.get("passRating")), acc_rating=number(row.get("accRating")),
                tech_rating=number(row.get("techRating")), modifiers=text(row.get("modifiers"), 80))


def recent_score(row):
    leaderboard = row["leaderboard"]
    song, diff = leaderboard["song"], leaderboard["difficulty"]
    score_id = int(row["id"])
    result = graph_point(dict(acc=row["accuracy"], leaderboardId=row["leaderboardId"],
        songName=song["name"], hash=song["hash"], mode=diff["modeName"], diff=diff["difficultyName"],
        timeset=row["timepost"], pp=row.get("pp"), modifiers=row.get("modifiers")))
    result.update(key=str(score_id), score_id=score_id, stars=number(diff.get("stars")),
        pass_rating=number(diff.get("passRating")), acc_rating=number(diff.get("accRating")),
        tech_rating=number(diff.get("techRating")), duration=number(diff.get("duration")),
        bpm=number(song.get("bpm")), nps=number(diff.get("nps")),
        bad_cuts=number(row.get("badCuts")), missed_notes=number(row.get("missedNotes")),
        bomb_cuts=number(row.get("bombCuts")), walls_hit=number(row.get("wallsHit")),
        pauses=number(row.get("pauses")), full_combo=row.get("fullCombo") is True,
        max_combo=number(row.get("maxCombo")),
        # These are average cut scores (out of 115), NOT percentage accuracies.
        left_cut=number(row.get("accLeft")), right_cut=number(row.get("accRight")),
        accuracy_gain=number((row.get("scoreImprovement") or {}).get("accuracy"), 0) * 100,
        pp_gain=number((row.get("scoreImprovement") or {}).get("pp")))
    # Base difficulty ratings are not modifier-adjusted. Do not mix them into rated comparisons.
    if result["modifiers"]:
        for key in ("stars", "pass_rating", "acc_rating", "tech_rating"):
            result[key] = None
    return result


async def fetch_snapshot(player_id: str):
    async with httpx.AsyncClient(timeout=httpx.Timeout(20, connect=8),
                                headers={"User-Agent": "CHIPS/0.1 personal-dashboard"}) as client:
        profile = await get_json(client, f"{BEATLEADER}/player/{player_id}")
        canonical_id = str(profile.get("id", ""))
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,25}", canonical_id):
            raise UpstreamError("Invalid player response.")
        graph = await get_json(client, f"{BEATLEADER}/player/{canonical_id}/accgraph", {"no_unranked_stars": "true"})
        history = await get_json(client, f"{BEATLEADER}/player/{canonical_id}/history", {"count": 30})
        scores, total = {}, 0
        # Bounded snapshot of retained scores, not a claim of full attempt history.
        for page in range(1, 4):
            body = await get_json(client, f"{BEATLEADER}/player/{canonical_id}/scores",
                {"sortBy": "date", "order": "desc", "count": 100, "page": page,
                 "includeIO": "true", "leaderboardContext": "general"})
            total = int(body["metadata"]["total"])
            for row in body.get("data") or []:
                parsed = recent_score(row)
                scores[parsed["key"]] = parsed
            if not body.get("data") or page * 100 >= total:
                break
        if not isinstance(graph, list) or len(graph) > 20000 or not isinstance(history, list):
            raise UpstreamError("Unexpected source data shape.")
        # Reject malformed core data atomically; retain the prior complete snapshot.
        points = {p["key"]: p for p in map(graph_point, graph)}
        daily = [dict(timestamp=epoch(h["timestamp"]), pp=number(h.get("pp")),
                      rank=number(h.get("rank")), country_rank=number(h.get("countryRank"))) for h in history[:30]]
        return dict(version=1, fetched_at=int(time.time()), player_id=canonical_id,
            profile=dict(name=text(profile.get("name")), pp=number(profile.get("pp")),
                         rank=number(profile.get("rank")), country=text(profile.get("country"), 4),
                         country_rank=number(profile.get("countryRank"))),
            graph=sorted(points.values(), key=lambda p: p["timestamp"]),
            scores=sorted(scores.values(), key=lambda p: p["timestamp"], reverse=True),
            history=sorted(daily, key=lambda h: h["timestamp"]), total_scores=total,
            coverage="Retained general-context scores; not every attempt. Latest 300 scores at most. Ranked graph is a separate portfolio.")


def map_detail(body, map_hash: str, mode: str, difficulty: str):
    version = next((v for v in body.get("versions", []) if str(v.get("hash", "")).lower() == map_hash), None)
    if version is None:
        raise UpstreamError("Exact map version is unavailable.", 404)
    diff = next((d for d in version.get("diffs", [])
                 if d.get("characteristic") == mode and d.get("difficulty") == difficulty), None)
    if diff is None:
        raise UpstreamError("This map difficulty is unavailable.", 404)
    metadata = body.get("metadata") or {}
    return dict(map_id=text(body.get("id"), 40), hash=map_hash, mode=mode, difficulty=difficulty,
        bpm=number(metadata.get("bpm")), duration=number(metadata.get("duration")),
        tags=[text(t, 80) for t in (body.get("tags") or [])[:30]],
        **{key: number(diff.get(key)) for key in ("njs", "nps", "notes", "bombs", "obstacles")})
