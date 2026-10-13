import sys
import os
import shutil
import tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Isolated data dir and two user libraries: user 1 gets a copy of the sample clip, user 2 the samples folder
_tmp = tempfile.mkdtemp(prefix="tvdiag_test_")
_samples = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sample_videos")
_user1_dir = os.path.join(_tmp, "user1")
os.makedirs(_user1_dir)
shutil.copy(os.path.join(_samples, "test_clip_75s.mp4"), os.path.join(_user1_dir, "user1_clip.mp4"))
os.environ["DATA_DIR"] = os.path.join(_tmp, "data")
os.environ["USER1_DIRS"] = _user1_dir
os.environ["USER2_DIRS"] = _samples

from fastapi.testclient import TestClient
from app.main import app
from app.database import init_db
from app.scanner import scan_directories_sync

init_db()
scan_directories_sync()
client = TestClient(app)

def login(pin):
    res = client.post("/api/v1/auth/verify", json={"pin": pin}).json()
    assert res["valid"] is True, res
    return {"X-Session-Token": res["token"]}

def test_status():
    res = client.get("/api/v1/status")
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["status"] == "online"
    assert data["video_count"] >= 1
    print("✓ Status check passed")

def test_help_endpoint():
    res = client.get("/help")
    assert res.status_code == 200
    assert "TV Diagnostics & Stealth Media Vault" in res.text
    print("✓ Help guide endpoint passed")

def test_pin_verification():
    res_bad = client.post("/api/v1/auth/verify", json={"pin": "000000"})
    assert res_bad.json()["valid"] is False

    u1 = client.post("/api/v1/auth/verify", json={"pin": "002007"}).json()
    assert u1["valid"] is True and u1["user_id"] == 1
    u2 = client.post("/api/v1/auth/verify", json={"pin": "482061"}).json()
    assert u2["valid"] is True and u2["user_id"] == 2

    # 123456 and 1234 must be strictly rejected
    res_rejected_6 = client.post("/api/v1/auth/verify", json={"pin": "123456"})
    assert res_rejected_6.json()["valid"] is False

    res_rejected_4 = client.post("/api/v1/auth/verify", json={"pin": "1234"})
    assert res_rejected_4.json()["valid"] is False
    print("✓ User 1 (002007) and user 2 (482061) unlock, 123456 & 1234 strictly rejected")

def test_user_libraries_are_separate():
    v1 = client.get("/api/v1/videos", headers=login("002007")).json()
    v2 = client.get("/api/v1/videos", headers=login("482061")).json()
    assert [v["filename"] for v in v1] == ["user1_clip.mp4"]
    assert "user1_clip.mp4" not in [v["filename"] for v in v2] and len(v2) >= 1
    # A user can't reach the other user's video by id
    assert client.get(f"/api/v1/videos/{v2[0]['id']}", headers=login("002007")).status_code == 404
    assert client.get(f"/api/v1/videos/{v1[0]['id']}/stream?t=" + login("482061")["X-Session-Token"]).status_code == 404
    # Admin console (no token) sees everything
    assert len(client.get("/api/v1/videos").json()) == len(v1) + len(v2)
    # Bad token is rejected so the TV relocks
    assert client.get("/api/v1/videos", headers={"X-Session-Token": "1.bogus"}).status_code == 401
    print("✓ Each user only sees videos from their own folders")

def test_change_and_reset_pin():
    old_session = login("002007")
    bad = client.post("/api/v1/auth/change-pin", json={"new_pin": "12ab56"}, headers=old_session)
    assert bad.status_code == 400
    taken = client.post("/api/v1/auth/change-pin", json={"new_pin": "482061"}, headers=old_session)
    assert taken.status_code == 409
    ok = client.post("/api/v1/auth/change-pin", json={"new_pin": "135790"}, headers=old_session)
    assert ok.status_code == 200, ok.text
    assert client.post("/api/v1/auth/verify", json={"pin": "002007"}).json()["valid"] is False
    assert client.get("/api/v1/videos", headers=old_session).status_code == 401
    new_session = {"X-Session-Token": ok.json()["token"]}
    assert client.get("/api/v1/videos", headers=new_session).status_code == 200

    users = {u["id"]: u for u in client.get("/api/v1/users").json()}
    assert users[1]["pin_is_default"] is False and users[2]["pin_is_default"] is True
    assert client.post("/api/v1/users/1/reset-pin").status_code == 200
    assert client.post("/api/v1/auth/verify", json={"pin": "002007"}).json()["valid"] is True
    assert client.post("/api/v1/auth/verify", json={"pin": "135790"}).json()["valid"] is False
    print("✓ User can change own code; backend reset restores default 002007")

def test_preview_frames(video_id):
    first = client.get(f"/api/v1/videos/{video_id}/preview/0")
    assert first.status_code == 200 and first.headers["content-type"] == "image/jpeg"
    # 75s clip: frames at 7s .. 70s are indices 0..9, index 10 (77s) is past the end
    assert client.get(f"/api/v1/videos/{video_id}/preview/9").status_code == 200
    assert client.get(f"/api/v1/videos/{video_id}/preview/10").status_code == 404
    print("✓ 7-second storyboard preview frames served")

def test_notes_pin_verification():
    res_bad = client.post("/api/v1/auth/verify-notes-pin", json={"pin": "0000"})
    assert res_bad.json()["valid"] is False
    
    res_good_4 = client.post("/api/v1/auth/verify-notes-pin", json={"pin": "1234"})
    assert res_good_4.json()["valid"] is True

    # 123456 must be rejected for notes pin
    res_rejected_6 = client.post("/api/v1/auth/verify-notes-pin", json={"pin": "123456"})
    assert res_rejected_6.json()["valid"] is False
    print("✓ Notes PIN 1234 passed, 123456 strictly rejected")

def test_videos_list():
    res = client.get("/api/v1/videos")
    assert res.status_code == 200
    videos = res.json()
    assert len(videos) >= 1
    # Pick a video whose file actually exists on disk
    v = next((item for item in videos if os.path.exists(item["filepath"])), videos[0])
    assert "duration" in v
    assert "rating" in v
    assert "watched_seconds" in v
    print(f"✓ Video list retrieved successfully: {v['title']}")
    return v["id"]

def test_byte_range_streaming(video_id):
    # Test HTTP 206 partial content
    headers = {"Range": "bytes=0-1023"}
    res = client.get(f"/api/v1/videos/{video_id}/stream", headers=headers)
    assert res.status_code == 206, f"Expected 206, got {res.status_code}"
    assert "bytes 0-1023/" in res.headers.get("content-range", "")
    assert len(res.content) == 1024
    print("✓ Direct Play HTTP 206 Byte Range streaming passed")

def test_thumbnail_retrieval(video_id):
    res = client.get(f"/api/v1/videos/{video_id}/thumbnail")
    assert res.status_code == 200
    assert res.headers.get("content-type") == "image/jpeg"
    assert len(res.content) > 1000
    print("✓ 60-second thumbnail served successfully")

def test_metadata_and_circle_ratings(video_id):
    # Set to 1 circle (Level 1) and custom note
    res = client.post(f"/api/v1/videos/{video_id}/metadata", json={
        "rating": 1,
        "notes": "Watched chapter 1, excellent quality",
        "tags": "action, thriller, sample"
    })
    assert res.status_code == 200
    
    v = client.get(f"/api/v1/videos/{video_id}").json()
    assert v["rating"] == 1
    assert v["notes"] == "Watched chapter 1, excellent quality"
    assert "action" in v["tags"]
    
    # Set to 2 circles (Level 2)
    res2 = client.post(f"/api/v1/videos/{video_id}/metadata", json={"rating": 2})
    assert res2.status_code == 200
    v2 = client.get(f"/api/v1/videos/{video_id}").json()
    assert v2["rating"] == 2
    print("✓ Discreet Circle ratings (0, 1, 2), notes, and tags update passed")

def test_tags_and_filtering(video_id):
    # Retrieve tags list
    res_tags = client.get("/api/v1/tags")
    assert res_tags.status_code == 200
    tags_data = res_tags.json()
    tag_names = [t["tag"] if isinstance(t, dict) else t for t in tags_data]
    assert "action" in tag_names
    assert "thriller" in tag_names
    
    # Filter videos by tag
    res_filtered = client.get("/api/v1/videos?tag=action")
    assert res_filtered.status_code == 200
    filtered_vids = res_filtered.json()
    assert any(v["id"] == video_id for v in filtered_vids)
    print("✓ Tag retrieval and video tag filtering passed")

def test_progress_tracking_and_reset(video_id):
    # Update progress to 45.5 seconds
    res = client.post(f"/api/v1/videos/{video_id}/progress", json={"watched_seconds": 45.5})
    assert res.status_code == 200
    
    v = client.get(f"/api/v1/videos/{video_id}").json()
    assert abs(v["watched_seconds"] - 45.5) < 0.1
    assert v["last_watched_at"] is not None
    print("✓ Video progress tracking passed (resumes from 45.5s)")
    
    # Reset progress from backend
    res_reset = client.post(f"/api/v1/videos/{video_id}/reset-progress")
    assert res_reset.status_code == 200
    
    v_reset = client.get(f"/api/v1/videos/{video_id}").json()
    assert v_reset["watched_seconds"] == 0.0
    print("✓ Backend Reset Playback Position passed (reset to 0.0s)")

if __name__ == "__main__":
    test_status()
    test_help_endpoint()
    test_pin_verification()
    test_notes_pin_verification()
    vid_id = test_videos_list()
    test_byte_range_streaming(vid_id)
    test_thumbnail_retrieval(vid_id)
    test_metadata_and_circle_ratings(vid_id)
    test_tags_and_filtering(vid_id)
    test_progress_tracking_and_reset(vid_id)
    test_user_libraries_are_separate()
    test_change_and_reset_pin()
    test_preview_frames(vid_id)
    print("\n🎉 ALL BACKEND TESTS PASSED!")
