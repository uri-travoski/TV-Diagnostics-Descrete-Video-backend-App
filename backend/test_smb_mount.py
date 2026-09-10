import sys
import os
import shutil
from pathlib import Path

sys.path.insert(0, os.path.abspath("backend"))

from app.database import init_db, get_all_videos, upsert_video
from app.scanner import (
    check_directory_readiness, evaluate_mounts,
    scan_directories_sync, get_mount_state
)
import app.config as config

def test_smb_mount_simulation():
    print("\n--- Testing SMB Mount Detection & Delayed Availability ---")
    init_db()

    # Create a simulated unmounted / empty mount directory
    test_smb_dir = Path("backend/data/test_smb_mount")
    if test_smb_dir.exists():
        shutil.rmtree(test_smb_dir)

    # 1. Test when directory does not exist at all (e.g. host hasn't mounted SMB yet)
    config.VIDEO_DIRS = [test_smb_dir]
    readiness_nonexistent = check_directory_readiness(test_smb_dir)
    assert not readiness_nonexistent["is_ready"]
    assert "waiting for mount" in readiness_nonexistent["reason"]
    print("✓ Non-existent SMB mountpoint correctly detected as unready")

    # 2. Test when empty mountpoint folder exists (e.g. Linux /media/smb mountpoint before cifs mount)
    test_smb_dir.mkdir(parents=True, exist_ok=True)
    readiness_empty = check_directory_readiness(test_smb_dir)
    assert not readiness_empty["is_ready"]
    assert "Mountpoint is empty" in readiness_empty["reason"]
    print("✓ Empty SMB mountpoint correctly detected as unready (waiting for drive)")

    # 3. Verify evaluate_mounts reports "waiting_for_mount"
    is_ready = evaluate_mounts()
    assert not is_ready
    state = get_mount_state()
    assert state["status"] == "waiting_for_mount"
    print("✓ Server mount state set to 'waiting_for_mount'")

    # 4. Verify scan does not delete existing DB entries when SMB is unmounted
    # Insert a dummy video in DB
    dummy_meta = {
        "filepath": "/media/smb/sample.mp4",
        "filename": "sample.mp4",
        "title": "Sample Movie",
        "filesize": 1000,
        "duration": 120.0,
        "width": 1920,
        "height": 1080,
        "video_codec": "h264",
        "audio_codec": "aac",
        "audio_channels": 2,
        "thumbnail_path": ""
    }
    upsert_video(dummy_meta)
    vids_before = get_all_videos()
    assert len(vids_before) >= 1

    scan_res = scan_directories_sync()
    assert scan_res["status"] == "waiting_for_mount"
    
    # Check DB still has our video (NOT pruned!)
    vids_after = get_all_videos()
    assert len(vids_after) == len(vids_before)
    print("✓ Scanner safely aborted without wiping database while SMB is unmounted")

    # 5. Simulate SMB drive mounting (file appears in folder)
    # Copy our sample clip into the simulated SMB directory
    shutil.copy("backend/sample_videos/test_clip_75s.mp4", str(test_smb_dir / "smb_video.mp4"))
    
    readiness_mounted = check_directory_readiness(test_smb_dir)
    assert readiness_mounted["is_ready"]
    assert readiness_mounted["has_media"]
    print("✓ SMB drive mount detected when files become accessible")

    # 6. Evaluate mounts now returns True
    assert evaluate_mounts() is True
    assert get_mount_state()["status"] == "ready"
    print("✓ Server mount state transitioned to 'ready'")

    # 7. Perform scan now that SMB is mounted
    scan_success = scan_directories_sync()
    assert scan_success["status"] == "completed"
    assert scan_success["indexed_count"] >= 1
    print(f"✓ Scan completed after SMB mount: {scan_success['indexed_count']} videos indexed")

    # Clean up test directory and dummy DB entry
    from app.database import get_connection
    conn = get_connection()
    conn.cursor().execute("DELETE FROM videos WHERE filepath LIKE '%test_smb_mount%' OR filepath = '/media/smb/sample.mp4'")
    conn.commit()
    conn.close()

    if test_smb_dir.exists():
        shutil.rmtree(test_smb_dir)

    # Restore default video dirs
    config.VIDEO_DIRS = [Path("backend/sample_videos")]
    scan_directories_sync()

    print("\n🎉 ALL SMB DELAYED MOUNT TESTS PASSED!")

if __name__ == "__main__":
    test_smb_mount_simulation()

