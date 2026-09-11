import sys
import os
sys.path.insert(0, os.path.abspath("backend"))

from fastapi.testclient import TestClient
from app.main import app
from app.database import init_db

init_db()
client = TestClient(app)

def test_status():
    res = client.get("/api/v1/status")
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["status"] == "online"
    assert data["video_count"] >= 1
    print("✓ Status check passed")

def test_pin_verification():
    res_bad = client.post("/api/v1/auth/verify", json={"pin": "000000"})
    assert res_bad.json()["valid"] is False

    res_good_default = client.post("/api/v1/auth/verify", json={"pin": "482061"})
    assert res_good_default.json()["valid"] is True
    
    # 123456 and 1234 must be strictly rejected
    res_rejected_6 = client.post("/api/v1/auth/verify", json={"pin": "123456"})
    assert res_rejected_6.json()["valid"] is False

    res_rejected_4 = client.post("/api/v1/auth/verify", json={"pin": "1234"})
    assert res_rejected_4.json()["valid"] is False
    print("✓ Default 482061 passed, 123456 & 1234 strictly rejected")

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
    test_pin_verification()
    test_notes_pin_verification()
    vid_id = test_videos_list()
    test_byte_range_streaming(vid_id)
    test_thumbnail_retrieval(vid_id)
    test_metadata_and_circle_ratings(vid_id)
    test_tags_and_filtering(vid_id)
    test_progress_tracking_and_reset(vid_id)
    print("\n🎉 ALL BACKEND TESTS PASSED!")
