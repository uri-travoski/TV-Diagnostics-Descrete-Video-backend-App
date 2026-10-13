import asyncio
import re
import shutil
import hashlib
import hmac
import threading
from pathlib import Path
from typing import Optional, List
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, HTTPException, Depends, status
from fastapi.responses import HTMLResponse, FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field

from .config import (
    BASE_DIR, HOST, PORT, NOTES_PIN, USERS,
    VIDEO_DIRS, SCAN_ON_STARTUP, SCAN_INTERVAL_MINUTES, APP_VERSION,
    THUMBNAILS_DIR
)
from .database import (
    init_db, get_all_videos, get_video,
    update_progress, reset_progress, reset_all_progress,
    update_metadata, get_all_tags,
    get_session_secret, get_user_pins, find_user_by_pin, set_user_pin
)
from .metadata import generate_preview_frame

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

class ChangePinRequest(BaseModel):
    new_pin: str

class ProgressUpdateRequest(BaseModel):
    watched_seconds: float
    completed: Optional[bool] = None

class MetadataUpdateRequest(BaseModel):
    title: Optional[str] = None
    notes: Optional[str] = None
    rating: Optional[int] = Field(default=None, ge=0, le=2)
    tags: Optional[str] = None


# --- TV user sessions ---
# The TV app unlocks with a user's 6-digit PIN and gets back a session token
# "<user_id>.<hmac>" that it sends with every call (X-Session-Token header, or ?t= on
# stream / image URLs). The HMAC covers the user's current PIN, so changing or resetting
# a PIN logs out that user's TVs. Calls without a token are the admin console and see
# every user's videos.

SESSION_HEADER = "X-Session-Token"

def _sign(user_id: int, pin: str) -> str:
    return hmac.new(get_session_secret().encode(), f"{user_id}:{pin}".encode(), hashlib.sha256).hexdigest()[:32]

def make_session_token(user_id: int, pin: str) -> str:
    return f"{user_id}.{_sign(user_id, pin)}"

def user_dirs(user_id: int) -> List[str]:
    return [str(Path(d).resolve()) for d in USERS[user_id]["dirs"]]

def get_session_user(request: Request) -> Optional[int]:
    """Returns the TV user for the request's token, None for admin calls; 401 if the token is stale."""
    token = request.headers.get(SESSION_HEADER) or request.query_params.get("t")
    if not token:
        return None
    user_id_str, _, sig = token.partition(".")
    try:
        user_id = int(user_id_str)
    except ValueError:
        user_id = -1
    pin = get_user_pins().get(user_id)
    if user_id not in USERS or pin is None or not hmac.compare_digest(sig, _sign(user_id, pin)):
        raise HTTPException(status_code=401, detail="Session expired")
    return user_id

def scope_dirs(user_id: Optional[int]) -> Optional[List[str]]:
    return None if user_id is None else user_dirs(user_id)

def get_scoped_video(video_id: int, user_id: Optional[int]) -> dict:
    """The video, or 404 if it doesn't exist or isn't in this TV user's folders."""
    video = get_video(video_id)
    if video and user_id is not None:
        if not any(video["filepath"].startswith(d.rstrip("/") + "/") for d in user_dirs(user_id)):
            video = None
    if not video:
        raise HTTPException(status_code=404, detail="Video not found")
    return video

def validate_new_pin(user_id: int, pin: str) -> str:
    pin = pin.strip()
    if not re.fullmatch(r"\d{6}", pin):
        raise HTTPException(status_code=400, detail="Code must be exactly 6 digits")
    taken = {p for uid, p in get_user_pins().items() if uid != user_id}
    taken |= {u["default_pin"] for uid, u in USERS.items() if uid != user_id}
    if pin in taken:
        raise HTTPException(status_code=409, detail="That code can't be used, choose another")
    return pin

# ffmpeg is heavy on SMB-mounted files: limit concurrent preview frame extraction
_preview_slots = threading.Semaphore(2)


# --- Routes ---

@app.get("/", response_class=HTMLResponse)
async def decoy_root(request: Request):
    """Discreet decoy root page simulating TV Diagnostic utility."""
    return templates.TemplateResponse(request, "decoy.html", {"version": APP_VERSION})

@app.get("/admin", response_class=HTMLResponse)
async def admin_portal(request: Request, pin: Optional[str] = None):
    """Media library management portal."""
    return templates.TemplateResponse(request, "admin.html", {"version": APP_VERSION})

@app.get("/help", response_class=HTMLResponse)
async def help_portal(request: Request):
    """Interactive user manual and TV app setup guide with screenshots."""
    return templates.TemplateResponse(request, "help.html", {"version": APP_VERSION})


@app.post("/api/v1/auth/verify")
async def verify_pin(req: PinVerifyRequest):
    """Unlocks the TV vault: the PIN picks the user, whose folders the session is limited to."""
    entered = req.pin.strip()
    user_id = find_user_by_pin(entered) if entered else None
    if user_id is None:
        return {"valid": False}
    return {
        "valid": True,
        "user_id": user_id,
        "user_name": USERS[user_id]["name"],
        "token": make_session_token(user_id, entered)
    }

@app.post("/api/v1/auth/change-pin")
async def change_pin(req: ChangePinRequest, request: Request):
    """Lets a TV user change their own 6-digit code. Returns a fresh session token."""
    user_id = get_session_user(request)
    if user_id is None:
        raise HTTPException(status_code=401, detail="Not signed in")
    pin = validate_new_pin(user_id, req.new_pin)
    set_user_pin(user_id, pin)
    return {"success": True, "token": make_session_token(user_id, pin)}

@app.get("/api/v1/users")
async def list_users():
    """Admin console: the TV users, their folders and whether their code was changed."""
    pins = get_user_pins()
    return [
        {
            "id": user_id,
            "name": user["name"],
            "dirs": [str(d) for d in user["dirs"]],
            "video_count": len(get_all_videos(dirs=user_dirs(user_id))),
            "pin_is_default": pins.get(user_id) == user["default_pin"]
        }
        for user_id, user in USERS.items()
    ]

@app.post("/api/v1/users/{user_id}/reset-pin")
async def reset_user_pin(user_id: int):
    """Admin console: restores a user's default code (and signs out their TVs)."""
    if user_id not in USERS:
        raise HTTPException(status_code=404, detail="User not found")
    set_user_pin(user_id, USERS[user_id]["default_pin"])
    return {"success": True, "user_id": user_id}

@app.post("/api/v1/auth/verify-notes-pin")
async def verify_notes_pin(req: NotesPinVerifyRequest):
    """Validates numeric PIN for enabling Notes in TV app settings (configured NOTES_PIN or default 1234)."""
    entered = req.pin.strip()
    valid_pins = {NOTES_PIN.strip(), "1234"}
    is_valid = entered in valid_pins
    return {"valid": is_valid}

@app.get("/api/v1/videos")
async def list_videos(
    search: Optional[str] = None,
    rating: Optional[int] = None,
    tag: Optional[str] = None,
    sort_by: str = "recent_desc",
    user_id: Optional[int] = Depends(get_session_user)
):
    """Lists indexed videos (a TV user's own folders only) with metadata, notes, tags, ratings and resume info."""
    videos = get_all_videos(search=search, rating=rating, tag=tag, sort_by=sort_by, dirs=scope_dirs(user_id))
    return videos

@app.get("/api/v1/tags")
async def list_tags(prefix: Optional[str] = None, user_id: Optional[int] = Depends(get_session_user)):
    """Lists all unique video tags with usage count, optionally filtered by prefix."""
    return get_all_tags(prefix=prefix, dirs=scope_dirs(user_id))


@app.get("/api/v1/videos/{video_id}")
async def retrieve_video(video_id: int, user_id: Optional[int] = Depends(get_session_user)):
    """Retrieves a single video by ID."""
    return get_scoped_video(video_id, user_id)

@app.get("/api/v1/videos/{video_id}/stream")
async def stream_video(video_id: int, request: Request, user_id: Optional[int] = Depends(get_session_user)):
    """Streams video with HTTP 206 Partial Content (Byte Range) Direct Play support."""
    video = get_scoped_video(video_id, user_id)
    return get_media_response(video["filepath"], request)

@app.get("/api/v1/videos/{video_id}/thumbnail")
async def get_thumbnail(video_id: int, user_id: Optional[int] = Depends(get_session_user)):
    """Serves the generated 60-second frame thumbnail."""
    video = get_scoped_video(video_id, user_id)
    if not video["thumbnail_path"]:
        raise HTTPException(status_code=404, detail="Thumbnail not found")
        
    thumb_path = Path(video["thumbnail_path"])
    if not thumb_path.exists():
        raise HTTPException(status_code=404, detail="Thumbnail file missing on disk")
        
    return FileResponse(str(thumb_path), media_type="image/jpeg")

@app.get("/api/v1/videos/{video_id}/preview/{index}")
def get_preview_frame(video_id: int, index: int, user_id: Optional[int] = Depends(get_session_user)):
    """Storyboard frame for the TV grid preview: the picture at (index + 1) * 7 seconds."""
    video = get_scoped_video(video_id, user_id)
    with _preview_slots:
        frame = generate_preview_frame(video["filepath"], video["duration"], index)
    if not frame:
        raise HTTPException(status_code=404, detail="Preview frame not available")
    return FileResponse(frame, media_type="image/jpeg", headers={"Cache-Control": "max-age=86400"})

@app.post("/api/v1/videos/{video_id}/progress")
async def record_progress(video_id: int, req: ProgressUpdateRequest, user_id: Optional[int] = Depends(get_session_user)):
    """Updates watch progress and completion status from TV playback heartbeat."""
    get_scoped_video(video_id, user_id)
    success = update_progress(video_id, req.watched_seconds, req.completed)
    if not success:
        raise HTTPException(status_code=404, detail="Video not found")
    return {"success": True, "video_id": video_id, "watched_seconds": req.watched_seconds}

@app.post("/api/v1/videos/{video_id}/reset-progress")
async def reset_video_progress(video_id: int, user_id: Optional[int] = Depends(get_session_user)):
    """Resets playback position to 0 (unwatched)."""
    get_scoped_video(video_id, user_id)
    success = reset_progress(video_id)
    if not success:
        raise HTTPException(status_code=404, detail="Video not found")
    return {"success": True, "video_id": video_id, "watched_seconds": 0.0}

@app.post("/api/v1/videos/reset-all-progress")
async def reset_all_videos_progress(user_id: Optional[int] = Depends(get_session_user)):
    """Resets playback progress for all indexed videos (a TV user's own videos only)."""
    count = reset_all_progress(dirs=scope_dirs(user_id))
    return {"success": True, "reset_count": count}

@app.post("/api/v1/videos/{video_id}/metadata")
async def update_video_metadata(video_id: int, req: MetadataUpdateRequest, user_id: Optional[int] = Depends(get_session_user)):
    """Updates video title (renaming physical file on hard drive), notes, tags, and discreet circle rating."""
    video = get_scoped_video(video_id, user_id)

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
        "user_count": len(USERS),
        "notes_pin_configured": bool(NOTES_PIN)
    }


