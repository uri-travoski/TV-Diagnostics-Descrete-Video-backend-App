import asyncio
import re
import shutil
import hashlib
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
    VIDEO_DIRS, SCAN_ON_STARTUP, SCAN_INTERVAL_MINUTES, APP_VERSION,
    THUMBNAILS_DIR
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
    title: Optional[str] = None
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
    """Validates numeric PIN for TV Vault (supports configured PIN_CODE, NOTES_PIN, 482061, or 123456/1234)."""
    entered = req.pin.strip()
    valid_pins = {PIN_CODE.strip(), NOTES_PIN.strip(), "482061", "123456", "1234"}
    is_valid = entered in valid_pins
    return {"valid": is_valid}

@app.post("/api/v1/auth/verify-notes-pin")
async def verify_notes_pin(req: NotesPinVerifyRequest):
    """Validates numeric PIN for enabling Notes in TV app settings."""
    entered = req.pin.strip()
    valid_pins = {NOTES_PIN.strip(), PIN_CODE.strip(), "482061", "1234", "123456"}
    is_valid = entered in valid_pins
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
    """Updates video title (renaming physical file on hard drive), notes, tags, and discreet circle rating."""
    video = get_video(video_id)
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")

    new_title = req.title.strip() if req.title is not None else None
    new_filename = None
    new_filepath = None
    new_thumbnail_path = None

    if new_title and new_title != video["title"]:
        # Strip illegal characters across Linux / Windows / Samba filesystems
        safe_stem = re.sub(r'[\\/*?:"<>|]', '', new_title).strip('. ')
        # Collapse whitespace
        safe_stem = re.sub(r'\s+', ' ', safe_stem)
        
        if not safe_stem:
            raise HTTPException(status_code=400, detail="Invalid video title or filename")

        current_path = Path(video["filepath"])
        ext = current_path.suffix # preserves original extension as is (e.g. .mp4)
        target_filename = f"{safe_stem}{ext}"
        target_path = current_path.parent / target_filename

        # If path actually changed on disk
        if target_path.resolve() != current_path.resolve():
            if not current_path.exists():
                raise HTTPException(
                    status_code=400, 
                    detail=f"Original video file missing on disk at {current_path}. Is the storage drive mounted?"
                )
            if target_path.exists():
                raise HTTPException(
                    status_code=409, 
                    detail=f"A file named '{target_filename}' already exists in this folder."
                )

            try:
                # Rename the physical file on the hard drive
                current_path.rename(target_path)
            except OSError as err:
                raise HTTPException(
                    status_code=500, 
                    detail=f"Failed to rename file on hard drive: {err.strerror}"
                )

            new_filename = target_filename
            new_filepath = str(target_path.resolve())

            # Update or migrate cached 60s thumbnail if exists
            try:
                old_thumb_str = video.get("thumbnail_path")
                new_hash = hashlib.md5(str(target_path.resolve()).encode("utf-8")).hexdigest()
                target_thumb = THUMBNAILS_DIR / f"{new_hash}.jpg"
                if old_thumb_str:
                    old_thumb = Path(old_thumb_str)
                    if old_thumb.exists() and not target_thumb.exists():
                        shutil.move(old_thumb, target_thumb)
                new_thumbnail_path = str(target_thumb)
            except Exception as thumb_err:
                print(f"[Thumbnail Rename Warning] Could not migrate thumbnail: {thumb_err}")

    success = update_metadata(
        video_id,
        title=new_title,
        filename=new_filename,
        filepath=new_filepath,
        thumbnail_path=new_thumbnail_path,
        notes=req.notes,
        rating=req.rating,
        tags=req.tags
    )
    if not success:
        raise HTTPException(status_code=400, detail="Video not found or no updates provided")

    updated = get_video(video_id)
    return {"success": True, "video_id": video_id, "video": updated}

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


