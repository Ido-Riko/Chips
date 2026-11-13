import base64
from uuid import uuid4
import pytest
from test_activities import api, headers, body


def manifest(**changes):
    return dict(id=str(uuid4()), started=1000, ended=5000, status="stopped", zone_id="Asia/Jerusalem",
                sample_count=3, run_count=1, event_count=1) | changes


def run():
    return dict(bpm=70, received=1000, elapsed=10, last_received=3000, last_elapsed=2010,
                sample_count=3, timing=base64.b64encode(bytes([232, 7, 208, 15] * 2)).decode(), epoch=0)


def finish(api, record):
    path = f"/api/v1/heart-rate/{record['id']}"
    assert api.put(path, json=record, headers=headers()).status_code == 200
    assert api.put(path + "/runs/0", json={"records": [run()]}, headers=headers()).status_code == 200
    assert api.put(path + "/events/0", json={"records": [dict(at=4000, state="disconnected")]}, headers=headers()).status_code == 200
    reply = api.post(path + "/complete", headers=headers())
    assert reply.status_code == 200, reply.text
    return path


def test_lost_acknowledgements_retry_exact_readings_and_link_only_after_complete(api):
    record = manifest()
    path = f"/api/v1/heart-rate/{record['id']}"
    activity = body(type="BEAT_SABER", hr_session_id=record["id"])
    activity_path = f"/api/v1/activities/{activity['id']}"
    assert api.put(activity_path, json=activity, headers=headers()).status_code == 409
    assert api.put(path, json=record, headers=headers()).json()["complete"] is False
    assert api.post(path + "/complete", headers=headers()).status_code == 409
    assert api.get(path, headers=headers()).status_code == 409
    for _ in range(3):
        finish(api, record)
        assert api.put(activity_path, json=activity, headers=headers()).status_code == 200
    assert api.get(path, headers=headers()).json()["manifest"] == record
    assert api.get(path + "/runs/0", headers=headers()).json()["records"] == [run()]
    activities = api.get("/api/v1/activities", headers=headers()).json()
    assert len(activities) == 1 and activities[0]["hr_session_id"] == record["id"]
    assert api.put(path, json=record | dict(ended=6000), headers=headers()).status_code == 409
    assert api.put(path + "/runs/0", json={"records": [run() | dict(bpm=71)]}, headers=headers()).status_code == 409
    duplicate = activity | dict(id=str(uuid4()))
    assert api.put(f"/api/v1/activities/{duplicate['id']}", json=duplicate, headers=headers()).status_code == 409
    assert api.put(activity_path, json=activity | dict(hr_session_id=None, revision=2), headers=headers()).status_code == 409


def test_owner_isolation_and_origin_device_guard_with_independent_deletion(api):
    record = manifest()
    path = finish(api, record)
    for token in ["invalid", "revoked-token"]:
        assert api.get(path, headers=headers(token)).status_code == 401
    assert api.get(path, headers=headers("stranger-token")).status_code == 404
    assert api.get(path + "/runs/0", headers=headers("stranger-token")).status_code == 404
    assert api.get(path, headers=headers("other-device")).status_code == 200
    assert api.put(path, json=record, headers=headers("other-device")).status_code == 409
    assert api.delete(path, headers=headers("other-device")).status_code == 409
    activity = body(type="BEAT_SABER", hr_session_id=record["id"])
    activity_path = f"/api/v1/activities/{activity['id']}"
    assert api.put(activity_path, json=activity, headers=headers("other-device")).status_code == 409
    assert api.put(activity_path, json=activity, headers=headers()).status_code == 200
    assert api.delete(path, headers=headers()).json() == dict(id=record["id"], deleted=True)
    assert api.delete(path, headers=headers()).status_code == 200
    assert api.get(path, headers=headers()).status_code == 410
    assert api.get(path + "/runs/0", headers=headers()).status_code == 410
    assert api.put(path, json=record, headers=headers()).status_code == 410
    assert not api.get(activity_path, headers=headers()).json()["deleted"]
    assert api.put(activity_path, json=activity | dict(note="Still saved", revision=2), headers=headers()).status_code == 200
    assert api.delete(activity_path, headers=headers()).json()["hr_session_id"] == record["id"]


def test_delete_before_upload_blocks_resurrection_and_activity_delete_keeps_hr(api):
    record = manifest()
    path = f"/api/v1/heart-rate/{record['id']}"
    assert api.delete(path, headers=headers()).status_code == 200
    assert api.put(path, json=record, headers=headers()).status_code == 410
    second = manifest()
    second_path = finish(api, second)
    activity = body(type="BEAT_SABER", hr_session_id=second["id"])
    activity_path = f"/api/v1/activities/{activity['id']}"
    assert api.put(activity_path, json=activity, headers=headers()).status_code == 200
    assert api.delete(activity_path, headers=headers()).status_code == 200
    assert api.get(second_path + "/runs/0", headers=headers()).json()["records"] == [run()]


@pytest.mark.parametrize("change", [dict(timing="!"), dict(timing=""), dict(last_received=3001), dict(sample_count=257), dict(bpm=0), dict(epoch=-1)])
def test_rejects_corrupt_or_inconsistent_timings(api, change):
    record = manifest()
    path = f"/api/v1/heart-rate/{record['id']}"
    assert api.put(path, json=record, headers=headers()).status_code == 200
    assert api.put(path + "/runs/0", json={"records": [run() | change]}, headers=headers()).status_code == 422


def test_validates_manifest_chunk_ranges_and_full_sample_count(api):
    record = manifest(sample_count=4)
    path = f"/api/v1/heart-rate/{record['id']}"
    assert api.put(path, json=record | dict(run_count=0), headers=headers()).status_code == 422
    assert api.put(path, json=record | dict(zone_id="invalid"), headers=headers()).status_code == 422
    assert api.put(path, json=record, headers=headers()).status_code == 200
    assert api.put(path + "/runs/1", json={"records": [run()]}, headers=headers()).status_code == 422
    assert api.put(path + "/runs/0", json={"records": [run()] * 129}, headers=headers()).status_code == 422
    assert api.put(path + "/runs/0", json={"records": [run()]}, headers=headers()).status_code == 200
    assert api.put(path + "/events/0", json={"records": [dict(at=4000, state="disconnected")]}, headers=headers()).status_code == 200
    assert api.post(path + "/complete", headers=headers()).status_code == 409
