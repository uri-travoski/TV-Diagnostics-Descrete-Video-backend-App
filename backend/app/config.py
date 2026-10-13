import os
from pathlib import Path

# Base Paths
BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = Path(os.getenv("DATA_DIR", str(BASE_DIR / "data")))
DB_DIR = DATA_DIR / "db"
THUMBNAILS_DIR = DATA_DIR / "thumbnails"

APP_VERSION = "1.2.0"

# Ensure directories exist
DB_DIR.mkdir(parents=True, exist_ok=True)
THUMBNAILS_DIR.mkdir(parents=True, exist_ok=True)

BACKUP_DIR = DB_DIR / "backups"
BACKUP_DIR.mkdir(parents=True, exist_ok=True)

DB_PATH = DB_DIR / "tv_diagnostics.db"

# Server Configuration
HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8090"))

# Security
NOTES_PIN = os.getenv("NOTES_PIN", "1234")  # 4-digit PIN for TV Notes visibility unlock

# Admin console login (/admin and every API call made without a TV session token).
# With no ADMIN_PASSWORD set, the console stays locked.
ADMIN_USER = os.getenv("ADMIN_USER", "admin").strip()
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", "")
ADMIN_SESSION_HOURS = int(os.getenv("ADMIN_SESSION_HOURS", "24"))

# Activity log (playback, unlocks, wrong codes) shown on /admin; older entries are deleted
ACTIVITY_RETENTION_DAYS = int(os.getenv("ACTIVITY_RETENTION_DAYS", "180"))


# Video Directories to scan (supports comma-separated list of paths)
default_video_dir = str(BASE_DIR / "sample_videos")
if os.path.exists("/videos"):
    default_video_dir = "/videos"

def _parse_dirs(raw: str) -> list:
    return [Path(p.strip()) for p in raw.split(",") if p.strip()]

# Two TV users, each unlocking with their own 6-digit PIN and seeing only their own folders.
# The defaults below are what the admin console's "Reset code" button restores.
# Backwards compatible: when USER1_DIRS / USER2_DIRS are not set, the legacy VIDEO_DIRS
# library belongs to user 2 (whose default PIN 482061 was the old single vault PIN).
USERS = {
    1: {
        "name": os.getenv("USER1_NAME", "User 1"),
        "default_pin": os.getenv("USER1_DEFAULT_PIN", "002007"),
        "dirs": _parse_dirs(os.getenv("USER1_DIRS", "")),
    },
    2: {
        "name": os.getenv("USER2_NAME", "User 2"),
        "default_pin": os.getenv("USER2_DEFAULT_PIN", "482061"),
        "dirs": _parse_dirs(os.getenv("USER2_DIRS", os.getenv("VIDEO_DIRS", default_video_dir))),
    },
}

# Every folder that gets scanned: the union of all users' folders. Nothing else on the mount
# is scanned (VIDEO_DIRS only matters as user 2's library when USER2_DIRS isn't set).
VIDEO_DIRS = []
for _p in [d for u in USERS.values() for d in u["dirs"]]:
    if _p not in VIDEO_DIRS:
        VIDEO_DIRS.append(_p)

# Storyboard previews on the TV thumbnail grid: one frame every PREVIEW_INTERVAL_SECONDS
PREVIEW_INTERVAL_SECONDS = 7
PREVIEWS_DIR = DATA_DIR / "previews"
PREVIEWS_DIR.mkdir(parents=True, exist_ok=True)

# Supported Video Extensions
SUPPORTED_EXTENSIONS = {
    ".mp4", ".mkv", ".avi", ".mov", ".webm",
    ".m4v", ".ts", ".mts", ".m2ts", ".flv"
}

# Scanner & SMB Mount options
SCAN_ON_STARTUP = os.getenv("SCAN_ON_STARTUP", "true").lower() in ("true", "1", "yes")
SCAN_INTERVAL_MINUTES = int(os.getenv("SCAN_INTERVAL_MINUTES", "30"))
MOUNT_POLL_INTERVAL_SECONDS = int(os.getenv("MOUNT_POLL_INTERVAL_SECONDS", "5"))
ALLOW_PRUNE = os.getenv("ALLOW_PRUNE", "false").lower() in ("true", "1", "yes")
# Rescan within seconds of files being added, renamed or removed (inotify). Network mounts
# (SMB/NFS) don't report changes made by other machines: the periodic scan still covers those.
WATCH_FOLDERS = os.getenv("WATCH_FOLDERS", "true").lower() in ("true", "1", "yes")
WATCH_DEBOUNCE_SECONDS = int(os.getenv("WATCH_DEBOUNCE_SECONDS", "10"))
# Build every video's 7-second preview frames in the background after each scan
PREGENERATE_PREVIEWS = os.getenv("PREGENERATE_PREVIEWS", "true").lower() in ("true", "1", "yes")

# Discreet Ratings: 0 = unrated ('○'), 1 = 1 circle ('●'), 2 = 2 circles ('●●')
RATING_MAP = {
    0: "○",
    1: "●",
    2: "●●"
}

