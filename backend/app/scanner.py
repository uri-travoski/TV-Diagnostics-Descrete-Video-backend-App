import os
import asyncio
from pathlib import Path
from typing import Dict, Any, List
from . import config
from .metadata import extract_metadata, generate_thumbnail
from .database import (
    upsert_video, delete_missing_videos,
    find_relocated_video, create_database_backup
)


# State tracking
_is_scanning = False
_mount_state = "waiting_for_mount"  # "ready", "waiting_for_mount", "scanning"
_mount_details: List[Dict[str, Any]] = []
_last_scan_result: Dict[str, Any] = {}

def is_scanning() -> bool:
    return _is_scanning

def get_mount_state() -> Dict[str, Any]:
    return {
        "status": _mount_state,
        "details": _mount_details,
        "is_scanning": _is_scanning,
        "last_scan": _last_scan_result
    }

def check_directory_readiness(dir_path: Path) -> Dict[str, Any]:
    """
    Checks if a media folder (such as an SMB mount) is mounted, accessible,
    and populated with files.
    """
    if not dir_path.exists():
        return {
            "path": str(dir_path),
            "is_ready": False,
            "reason": "Directory does not exist yet (waiting for mount)"
        }
    if not dir_path.is_dir():
        return {
            "path": str(dir_path),
            "is_ready": False,
            "reason": "Path is not a directory"
        }
    try:
        # Check if we can read directory contents
        entries = list(os.scandir(dir_path))
        if not entries:
            # Mount directory exists as an empty mountpoint (SMB drive not yet attached)
            return {
                "path": str(dir_path),
                "is_ready": False,
                "reason": "Mountpoint is empty (waiting for SMB drive to mount)"
            }
        
        # Check if there are any supported video files or subdirectories
        has_media_or_dirs = any(
            e.is_dir() or Path(e.name).suffix.lower() in config.SUPPORTED_EXTENSIONS
            for e in entries
        )


        return {
            "path": str(dir_path),
            "is_ready": True,
            "reason": "Mounted and accessible",
            "entry_count": len(entries),
            "has_media": has_media_or_dirs
        }
    except PermissionError:
        return {
            "path": str(dir_path),
            "is_ready": False,
            "reason": "Permission denied accessing mount"
        }
    except Exception as e:
        return {
            "path": str(dir_path),
            "is_ready": False,
            "reason": f"Access error: {str(e)}"
        }

def evaluate_mounts() -> bool:
    """
    Evaluates all configured VIDEO_DIRS.
    Returns True if at least one configured directory is ready and mounted.
    """
    global _mount_state, _mount_details
    details = []
    any_ready = False

    for video_dir in config.VIDEO_DIRS:
        status = check_directory_readiness(video_dir)
        details.append(status)
        if status["is_ready"]:
            any_ready = True

    _mount_details = details
    if any_ready:
        _mount_state = "ready"
    else:
        _mount_state = "waiting_for_mount"

    return any_ready

def scan_directories_sync() -> Dict[str, Any]:
    """Scans all configured video directories synchronously."""
    global _is_scanning, _last_scan_result
    if _is_scanning:
        return {"status": "already_running"}

    # Verify mount availability before scanning
    if not evaluate_mounts():
        print("[Scanner] SMB media folders are not available yet. Scan skipped to protect library.")
        return {
            "status": "waiting_for_mount",
            "message": "SMB mount not available yet. Waiting for mount...",
            "details": _mount_details
        }

    _is_scanning = True
    discovered_files = set()
    added_or_updated = 0

    try:
        for video_dir in config.VIDEO_DIRS:
            readiness = check_directory_readiness(video_dir)
            if not readiness["is_ready"]:
                print(f"[Scanner] Skipping unready folder: {video_dir} ({readiness['reason']})")
                continue

            print(f"[Scanner] Scanning mounted media directory: {video_dir}")
            for root, _, files in os.walk(video_dir):
                for file in files:
                    ext = Path(file).suffix.lower()
                    if ext in config.SUPPORTED_EXTENSIONS:
                        full_path = str(Path(root) / file)
                        discovered_files.add(full_path)

                        try:
                            # 1. Extract metadata via ffprobe
                            meta = extract_metadata(full_path)

                            # 2. Check if file was relocated and has an existing thumbnail
                            relocated = find_relocated_video(meta["filename"], meta["filesize"], meta["duration"])
                            if relocated and relocated.get("thumbnail_path") and os.path.exists(relocated["thumbnail_path"]):
                                thumb_path = relocated["thumbnail_path"]
                                print(f"[Scanner] Preserving existing thumbnail for relocated file: {file}")
                            else:
                                thumb_path = generate_thumbnail(full_path, meta["duration"])
                            meta["thumbnail_path"] = thumb_path

                            # 3. Store / reconnect in DB
                            upsert_video(meta)
                            added_or_updated += 1
                        except Exception as file_err:
                            print(f"[Scanner] Failed to process {full_path}: {file_err}")

        # Safety check before pruning: only prune if files were discovered
        deleted_count = 0
        if discovered_files:
            deleted_count = delete_missing_videos(discovered_files)
        else:
            print("[Scanner Safety] 0 files discovered in scan. Skipping pruning to prevent accidental wipe.")

        print(f"[Scanner] Scan complete. Processed {added_or_updated} files. Pruned {deleted_count} removed files.")

        # Create hot database backup snapshot upon scan completion
        create_database_backup()

        _last_scan_result = {
            "status": "completed",
            "indexed_count": added_or_updated,
            "pruned_count": deleted_count,
            "total_files": len(discovered_files)
        }
        return _last_scan_result
    finally:
        _is_scanning = False

async def scan_directories_async() -> Dict[str, Any]:
    """Runs scan in a background thread."""
    loop = asyncio.get_event_loop()
    return await loop.run_in_executor(None, scan_directories_sync)

async def mount_watcher_and_scanner_task():
    """
    Continuous background watcher:
    1. If SMB mount is not available on startup (e.g. host OS still mounting network shares),
       it keeps checking every MOUNT_POLL_INTERVAL_SECONDS until it becomes available.
    2. Once available, automatically triggers the initial media scan.
    3. Keeps monitoring health; if the SMB mount disconnects, it transitions back to
       waiting mode without deleting any database records.
    """
    poll_interval = max(2, config.MOUNT_POLL_INTERVAL_SECONDS)
    scheduled_interval = max(60, config.SCAN_INTERVAL_MINUTES * 60)
    
    last_known_mounted = False
    seconds_since_last_full_scan = 0
    attempt_counter = 0


    print(f"[Mount Watcher] SMB mount watcher started. Polling every {poll_interval}s...")

    while True:
        try:
            is_mounted = evaluate_mounts()
            attempt_counter += 1

            if not is_mounted:
                if last_known_mounted:
                    print("[Mount Watcher] WARNING: SMB media folder disconnected or unmounted! Pausing scans and protecting database...")
                    last_known_mounted = False
                
                if attempt_counter % 6 == 1:  # Log every ~30 seconds when waiting
                    unready_reasons = [f"{d['path']}: {d['reason']}" for d in _mount_details]
                    print(f"[Mount Watcher] Waiting for SMB mount to become available... ({', '.join(unready_reasons)})")
                
                await asyncio.sleep(poll_interval)
                continue

            # If we were previously waiting and now it is mounted:
            if not last_known_mounted:
                print(f"[Mount Watcher] SUCCESS: SMB media folder is mounted and accessible! Triggering scan...")
                last_known_mounted = True
                seconds_since_last_full_scan = 0
                await scan_directories_async()

            # If already mounted, check if it's time for scheduled periodic rescan
            seconds_since_last_full_scan += poll_interval
            if seconds_since_last_full_scan >= scheduled_interval:
                print("[Mount Watcher] Starting scheduled periodic media rescan...")
                seconds_since_last_full_scan = 0
                await scan_directories_async()

            await asyncio.sleep(poll_interval)

        except asyncio.CancelledError:
            print("[Mount Watcher] Watcher task cancelled.")
            break
        except Exception as e:
            print(f"[Mount Watcher] Error in mount watcher loop: {e}")
            await asyncio.sleep(poll_interval)
