import asyncio
from pathlib import Path
from typing import Optional, List
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, HTTPException, Depends, status
from fastapi.responses import HTMLResponse, FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field

from .config import (
    BASE_DIR, HOST, PORT, PIN_CODE, NOTES_PIN, AUTH_TOKEN,
    VIDEO_DIRS, SCAN_ON_STARTUP, SCAN_INTERVAL_MINUTES, APP_VERSION
)
from .database import (
    init_db, get_all_videos, get_video,
    update_progress, reset_progress, reset_all_progress,
    update_metadata, get_all_tags
)

from .scanner import (
    scan_directories_async, is_scanning,
    mount_watcher_and_scanner_task, get_mount_state
)
from .streamer import get_media_response

# Background task reference
_mount_watcher_task = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup:
    init_db()
    print("[Server] Database initialized.")
    
    # Start continuous SMB mount watcher & background scanner
    global _mount_watcher_task
    _mount_watcher_task = asyncio.create_task(mount_watcher_and_scanner_task())
        
    yield
    
    # Shutdown:
    if _mount_watcher_task:
        _mount_watcher_task.cancel()


app = FastAPI(
    title="TV Diagnostics System",
    description="Stealth Media Streaming & Diagnostic Engine",
    version=APP_VERSION,
    lifespan=lifespan
)

# Static & Templates
templates_dir = BASE_DIR / "app" / "templates"
static_dir = BASE_DIR / "app" / "static"

app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")
templates = Jinja2Templates(directory=str(templates_dir))

# Models
class PinVerifyRequest(BaseModel):
    pin: str

class NotesPinVerifyRequest(BaseModel):
    pin: str

class ProgressUpdateRequest(BaseModel):
    watched_seconds: float
    completed: Optional[bool] = None

class MetadataUpdateRequest(BaseModel):
    notes: Optional[str] = None
    rating: Optional[int] = Field(default=None, ge=0, le=2)
    tags: Optional[str] = None


# --- Routes ---

@app.get("/", response_class=HTMLResponse)
async def decoy_root(request: Request):
    """Discreet decoy root page simulating TV Diagnostic utility."""
    return templates.TemplateResponse(request, "decoy.html", {"version": APP_VERSION})

@app.get("/admin", response_class=HTMLResponse)
async def admin_portal(request: Request, pin: Optional[str] = None):
    """Media library management portal."""
    return templates.TemplateResponse(request, "admin.html", {"version": APP_VERSION})


@app.post("/api/v1/auth/verify")
async def verify_pin(req: PinVerifyRequest):
    """Validates 6-digit numeric PIN for TV Vault."""
    is_valid = (req.pin.strip() == PIN_CODE.strip())
    return {"valid": is_valid}

@app.post("/api/v1/auth/verify-notes-pin")
async def verify_notes_pin(req: NotesPinVerifyRequest):
    """Validates 4-digit numeric PIN for enabling Notes in TV app settings."""
    is_valid = (req.pin.strip() == NOTES_PIN.strip())
    return {"valid": is_valid}

@app.get("/api/v1/videos")
async def list_videos(
    search: Optional[str] = None,
    rating: Optional[int] = None,
    tag: Optional[str] = None,
    sort_by: str = "recent_desc"
):
    """Lists all indexed videos with metadata, 60s thumbnails, notes, tags, circle ratings, and resume info."""
    videos = get_all_videos(search=search, rating=rating, tag=tag, sort_by=sort_by)
    return videos

@app.get("/api/v1/tags")
async def list_tags(prefix: Optional[str] = None):
    """Lists all unique video tags with usage count, optionally filtered by prefix."""
    return get_all_tags(prefix=prefix)


@app.get("/api/v1/videos/{video_id}")
async def retrieve_video(video_id: int):
    """Retrieves a single video by ID."""
    video = get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    return video

@app.get("/api/v1/videos/{video_id}/stream")
async def stream_video(video_id: int, request: Request):
    """Streams video with HTTP 206 Partial Content (Byte Range) Direct Play support."""
    video = get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    return get_media_response(video["filepath"], request)

@app.get("/api/v1/videos/{video_id}/thumbnail")
async def get_thumbnail(video_id: int):
    """Serves the generated 60-second frame thumbnail."""
    video = get_video(video_id)
    if not video or not video["thumbnail_path"]:
        raise HTTPException(status_code=404, detail="Thumbnail not found")
        
    thumb_path = Path(video["thumbnail_path"])
    if not thumb_path.exists():
        raise HTTPException(status_code=404, detail="Thumbnail file missing on disk")
        
    return FileResponse(str(thumb_path), media_type="image/jpeg")

@app.post("/api/v1/videos/{video_id}/progress")
async def record_progress(video_id: int, req: ProgressUpdateRequest):
    """Updates watch progress and completion status from TV playback heartbeat."""
    success = update_progress(video_id, req.watched_seconds, req.completed)
    if not success:
        raise HTTPException(status_code=404, detail="Video not found")
    return {"success": True, "video_id": video_id, "watched_seconds": req.watched_seconds}

@app.post("/api/v1/videos/{video_id}/reset-progress")
async def reset_video_progress(video_id: int):
    """Resets playback position to 0 (unwatched)."""
    success = reset_progress(video_id)
    if not success:
        raise HTTPException(status_code=404, detail="Video not found")
    return {"success": True, "video_id": video_id, "watched_seconds": 0.0}

@app.post("/api/v1/videos/reset-all-progress")
async def reset_all_videos_progress():
    """Resets playback progress for all indexed videos."""
    count = reset_all_progress()
    return {"success": True, "reset_count": count}

@app.post("/api/v1/videos/{video_id}/metadata")
async def update_video_metadata(video_id: int, req: MetadataUpdateRequest):
    """Updates user notes, tags, and discreet circle rating (0: ○, 1: ●, 2: ●●)."""
    success = update_metadata(video_id, notes=req.notes, rating=req.rating, tags=req.tags)
    if not success:
        raise HTTPException(status_code=404, detail="Video not found or no updates provided")
    return {"success": True, "video_id": video_id}

@app.post("/api/v1/scan")
async def trigger_scan():
    """Triggers background media rescan and 60s thumbnail generation."""
    if is_scanning():
        return {"status": "scanning", "message": "A scan is already in progress."}
    result = await scan_directories_async()
    return result

@app.get("/api/v1/status")
async def server_status():
    """Returns general server diagnostics and SMB mount readiness."""
    all_vids = get_all_videos()
    mount_state = get_mount_state()
    return {
        "status": "online",
        "version": APP_VERSION,
        "mount_status": mount_state["status"],
        "mount_details": mount_state["details"],
        "is_scanning": mount_state["is_scanning"],
        "video_count": len(all_vids),
        "video_directories": [str(d) for d in VIDEO_DIRS],
        "pin_configured": bool(PIN_CODE),
        "notes_pin_configured": bool(NOTES_PIN)
    }


