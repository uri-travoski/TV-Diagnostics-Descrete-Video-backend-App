import os
from pathlib import Path

# Base Paths
BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = Path(os.getenv("DATA_DIR", str(BASE_DIR / "data")))
DB_DIR = DATA_DIR / "db"
THUMBNAILS_DIR = DATA_DIR / "thumbnails"

APP_VERSION = "1.0.2"

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
PIN_CODE = os.getenv("PIN_CODE", "482061")  # Default PIN for TV Vault unlock
NOTES_PIN = os.getenv("NOTES_PIN", "1234")  # 4-digit PIN for TV Notes visibility unlock
AUTH_TOKEN = os.getenv("AUTH_TOKEN", "tv-diag-secret-token")


# Video Directories to scan (supports comma-separated list of paths)
default_video_dir = str(BASE_DIR / "sample_videos")
if os.path.exists("/videos"):
    default_video_dir = "/videos"

VIDEO_DIRS_RAW = os.getenv("VIDEO_DIRS", default_video_dir)
VIDEO_DIRS = [Path(p.strip()) for p in VIDEO_DIRS_RAW.split(",") if p.strip()]

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

# Discreet Ratings: 0 = unrated ('○'), 1 = 1 circle ('●'), 2 = 2 circles ('●●')
RATING_MAP = {
    0: "○",
    1: "●",
    2: "●●"
}

