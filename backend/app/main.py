import asyncio
import re
import shutil
import hashlib
import hmac
import secrets
import threading
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional, List, Dict, Any
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, HTTPException, Depends, Form, status
from fastapi.responses import HTMLResponse, FileResponse, JSONResponse, RedirectResponse
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
    get_session_secret, get_user_pins, find_user_by_pin, set_user_pin,
    log_activity, get_activity, get_play_counts
)
from .metadata import generate_preview_frame
from . import admin_auth
from .admin_auth import is_admin, client_ip, login_limiter, code_limiter

from .scanner import (
    scan_directories_async, is_scanning,
    mount_watcher_and_scanner_task, get_mount_state,
    get_watch_state, get_preview_progress, prune_old_activity
)
from .streamer import get_media_response

# Background task reference
_mount_watcher_task = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup:
    init_db()
    prune_old_activity()
    print("[Server] Database initialized.")
    if not admin_auth.admin_configured():
        print("[Server] WARNING: ADMIN_PASSWORD is not set. The admin console stays locked until it is.")
    
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
    lifespan=lifespan,
    # No public API docs: they would list every route to anyone on the network
    docs_url=None,
    redoc_url=None,
    openapi_url=None
)

# Static & Templates
templates_dir = BASE_DIR / "app" / "templates"
static_dir = BASE_DIR / "app" / "static"

app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")
templates = Jinja2Templates(directory=str(templates_dir))

# Models
class PinVerifyRequest(BaseModel):
    pin: str
    device: Optional[str] = None

class NotesPinVerifyRequest(BaseModel):
    pin: str

class ChangePinRequest(BaseModel):
    new_pin: str

class ProgressUpdateRequest(BaseModel):
    watched_seconds: float
    completed: Optional[bool] = None

class ActivityEventRequest(BaseModel):
    session_id: str = Field(default="", max_length=64)
    event: str = Field(max_length=32)
    video_id: Optional[int] = None
    position: Optional[float] = None
    watched_seconds: Optional[float] = None
    detail: str = Field(default="", max_length=300)

class MetadataUpdateRequest(BaseModel):
    title: Optional[str] = None
    notes: Optional[str] = None
    rating: Optional[int] = Field(default=None, ge=0, le=2)
    tags: Optional[str] = None


# --- TV user sessions ---
# The TV app unlocks with a user's 6-digit PIN and gets back a session token
# "<user_id>.<hmac>" that it sends with every call (X-Session-Token header, or ?t= on
# stream / image URLs). The HMAC covers the user's current PIN, so changing or resetting
# a PIN logs out that user's TVs. Calls without a token are the admin console: they need the
# admin login cookie (see admin_auth) and see every user's videos.

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
        if is_admin(request):
            return None
        raise HTTPException(status_code=401, detail="Admin login required")
    user_id_str, _, sig = token.partition(".")
    try:
        user_id = int(user_id_str)
    except ValueError:
        user_id = -1
    pin = get_user_pins().get(user_id)
    if user_id not in USERS or pin is None or not hmac.compare_digest(sig, _sign(user_id, pin)):
        raise HTTPException(status_code=401, detail="Session expired")
    return user_id

def require_admin(request: Request) -> None:
    """Admin console only (never a TV session)."""
    if not is_admin(request):
        raise HTTPException(status_code=401, detail="Admin login required")

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

def library_roots(user_id: Optional[int]) -> List[str]:
    if user_id is not None:
        return user_dirs(user_id)
    return [str(Path(d).resolve()) for d in VIDEO_DIRS]

def video_folder(filepath: str, roots: List[str]) -> str:
    """The video's folder as the TV shows it: library folder name + subfolders, e.g. "both/holiday"."""
    best = ""
    for root in roots:
        prefix = root.rstrip("/") + "/"
        if filepath.startswith(prefix) and len(prefix) > len(best):
            best = prefix
    if not best:
        return ""
    rel_parent = filepath[len(best):].rpartition("/")[0]
    name = Path(best.rstrip("/")).name or best.rstrip("/")
    return f"{name}/{rel_parent}" if rel_parent else name

ACTIVITY_SESSION_HEADER = "X-Activity-Session"

def request_device(request: Request) -> str:
    return request.headers.get("X-Device-Name", "")[:100]

# ffmpeg is heavy on SMB-mounted files: limit concurrent preview frame extraction
_preview_slots = threading.Semaphore(2)


# --- Routes ---

@app.get("/", response_class=HTMLResponse)
async def decoy_root(request: Request):
    """Discreet decoy root page simulating TV Diagnostic utility."""
    return templates.TemplateResponse(request, "decoy.html", {"version": APP_VERSION})

def login_redirect(next_path: str) -> RedirectResponse:
    return RedirectResponse(f"/admin/login?next={next_path}", status_code=303)

@app.get("/admin", response_class=HTMLResponse)
async def admin_portal(request: Request):
    """Media library management portal (admin login required)."""
    if not is_admin(request):
        return login_redirect("/admin")
    return templates.TemplateResponse(request, "admin.html", {"version": APP_VERSION})

@app.get("/admin/login", response_class=HTMLResponse)
async def admin_login_page(request: Request, next: Optional[str] = None):
    if is_admin(request):
        return RedirectResponse(admin_auth.safe_next(next), status_code=303)
    return templates.TemplateResponse(request, "login.html", {
        "version": APP_VERSION,
        "next": admin_auth.safe_next(next),
        "error": None if admin_auth.admin_configured()
                 else "The admin console is locked: set ADMIN_USER and ADMIN_PASSWORD in the compose file and restart.",
        "username": ""
    })

@app.post("/admin/login", response_class=HTMLResponse)
async def admin_login(request: Request, username: str = Form(""), password: str = Form(""), next: str = Form("/admin")):
    ip = client_ip(request)
    wait = login_limiter.seconds_locked(ip)
    error = None
    if not admin_auth.admin_configured():
        error = "The admin console is locked: set ADMIN_USER and ADMIN_PASSWORD in the compose file and restart."
    elif wait:
        error = f"Too many wrong attempts. Try again in {wait} seconds."
    elif admin_auth.check_credentials(username, password):
        login_limiter.succeeded(ip)
        log_activity("admin_login", ip=ip, device=request.headers.get("User-Agent", ""), detail=username.strip())
        response = RedirectResponse(admin_auth.safe_next(next), status_code=303)
        response.set_cookie(
            admin_auth.COOKIE_NAME, admin_auth.make_cookie(),
            max_age=admin_auth.ADMIN_SESSION_HOURS * 3600, httponly=True, samesite="strict",
            secure=request.url.scheme == "https"
        )
        return response
    else:
        login_limiter.failed(ip)
        log_activity("admin_login_failed", ip=ip, device=request.headers.get("User-Agent", ""), detail=username.strip())
        error = "Wrong username or password."
    return templates.TemplateResponse(request, "login.html", {
        "version": APP_VERSION, "next": admin_auth.safe_next(next), "error": error, "username": username
    }, status_code=401)

@app.get("/admin/logout")
async def admin_logout():
    response = RedirectResponse("/admin/login", status_code=303)
    response.delete_cookie(admin_auth.COOKIE_NAME)
    return response

@app.get("/help", response_class=HTMLResponse)
async def help_portal(request: Request):
    """Interactive user manual and TV app setup guide with screenshots (admin login required)."""
    if not is_admin(request):
        return login_redirect("/help")
    return templates.TemplateResponse(request, "help.html", {"version": APP_VERSION})


@app.post("/api/v1/auth/verify")
async def verify_pin(req: PinVerifyRequest, request: Request):
    """Unlocks the TV vault: the PIN picks the user, whose folders the session is limited to."""
    entered = req.pin.strip()
    ip = client_ip(request)
    device = (req.device or request_device(request))[:100]
    # Locked out after too many wrong codes: not even a correct code gets in until it ends
    locked = code_limiter.seconds_locked(ip)
    if locked:
        return {"valid": False, "locked_seconds": locked}
    user_id = find_user_by_pin(entered) if entered else None
    if user_id is None:
        lockout = code_limiter.failed(ip)
        if lockout:
            log_activity("code_lockout", ip=ip, device=device, detail=f"{lockout // 60} min")
            return {"valid": False, "locked_seconds": lockout}
        log_activity("wrong_code", ip=ip, device=device)
        return {"valid": False, "attempts_left": code_limiter.attempts_left(ip)}
    code_limiter.clear(ip)
    # Each unlock starts an activity session; the TV tags its playback events with it
    session_id = secrets.token_hex(8)
    log_activity("unlock", session_id=session_id, user_id=user_id, ip=ip, device=device)
    return {
        "valid": True,
        "user_id": user_id,
        "user_name": USERS[user_id]["name"],
        "token": make_session_token(user_id, entered),
        "session_id": session_id
    }

@app.post("/api/v1/auth/change-pin")
async def change_pin(req: ChangePinRequest, request: Request):
    """Lets a TV user change their own 6-digit code. Returns a fresh session token."""
    user_id = get_session_user(request)
    if user_id is None:
        raise HTTPException(status_code=401, detail="Not signed in")
    pin = validate_new_pin(user_id, req.new_pin)
    set_user_pin(user_id, pin)
    log_activity("code_changed", session_id=request.headers.get(ACTIVITY_SESSION_HEADER, "")[:64],
                 user_id=user_id, ip=client_ip(request), device=request_device(request))
    return {"success": True, "token": make_session_token(user_id, pin)}

@app.get("/api/v1/users", dependencies=[Depends(require_admin)])
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

@app.post("/api/v1/users/{user_id}/reset-pin", dependencies=[Depends(require_admin)])
async def reset_user_pin(user_id: int, request: Request):
    """Admin console: restores a user's default code (and signs out their TVs)."""
    if user_id not in USERS:
        raise HTTPException(status_code=404, detail="User not found")
    set_user_pin(user_id, USERS[user_id]["default_pin"])
    log_activity("code_reset", user_id=user_id, ip=client_ip(request), detail="Reset to default from admin console")
    return {"success": True, "user_id": user_id}

@app.get("/api/v1/lockouts", dependencies=[Depends(require_admin)])
async def list_lockouts():
    """Admin console: TVs (IP addresses) currently locked out of code entry, seconds remaining."""
    return [{"ip": ip, "seconds_left": s} for ip, s in sorted(code_limiter.active_lockouts().items())]

@app.post("/api/v1/lockouts/{ip}/clear", dependencies=[Depends(require_admin)])
async def clear_lockout(ip: str, request: Request):
    """Admin console: lets a locked-out TV enter codes again right away."""
    code_limiter.clear(ip)
    log_activity("code_unlocked", ip=ip, detail="Unlocked from admin console")
    return {"success": True}

@app.post("/api/v1/auth/verify-notes-pin")
async def verify_notes_pin(req: NotesPinVerifyRequest):
    """Validates the PIN for showing Notes in the TV app: only the configured NOTES_PIN."""
    entered = req.pin.strip()
    return {"valid": bool(entered) and hmac.compare_digest(entered, NOTES_PIN.strip())}

@app.get("/api/v1/videos")
async def list_videos(
    search: Optional[str] = None,
    rating: Optional[int] = None,
    tag: Optional[str] = None,
    sort_by: str = "recent_desc",
    user_id: Optional[int] = Depends(get_session_user)
):
    """
    Lists indexed videos (a TV user's own folders only) with metadata, notes, tags, ratings and resume
    info, plus each video's library folder and how many times this user (admin: anyone) started it.
    """
    videos = get_all_videos(search=search, rating=rating, tag=tag, sort_by=sort_by, dirs=scope_dirs(user_id))
    roots = library_roots(user_id)
    plays = get_play_counts(user_id)
    for v in videos:
        v["folder"] = video_folder(v["filepath"], roots)
        v["play_count"] = plays.get(v["id"], 0)
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

@app.post("/api/v1/scan", dependencies=[Depends(require_admin)])
async def trigger_scan():
    """Triggers background media rescan and 60s thumbnail generation."""
    if is_scanning():
        return {"status": "scanning", "message": "A scan is already in progress."}
    result = await scan_directories_async()
    return result

@app.get("/api/v1/status")
async def server_status(request: Request):
    """Server health. The TV's connection test sees only online/version; the admin console gets the details."""
    if not is_admin(request):
        return {"status": "online", "version": APP_VERSION}
    all_vids = get_all_videos()
    mount_state = get_mount_state()
    return {
        "status": "online",
        "version": APP_VERSION,
        "folder_watch": get_watch_state(),
        "previews": get_preview_progress(),
        "mount_status": mount_state["status"],
        "mount_details": mount_state["details"],
        "is_scanning": mount_state["is_scanning"],
        "video_count": len(all_vids),
        "video_directories": [str(d) for d in VIDEO_DIRS],
        "user_count": len(USERS),
        "notes_pin_configured": bool(NOTES_PIN)
    }


# --- Activity log ---

ACTIVITY_EVENTS = {"play", "pause", "resume", "seek", "speed", "ended", "exit", "lock"}

@app.post("/api/v1/activity")
async def record_activity(req: ActivityEventRequest, request: Request, user_id: Optional[int] = Depends(get_session_user)):
    """Playback and lock events from a TV, filed under the session started by its code entry."""
    if user_id is None:
        raise HTTPException(status_code=401, detail="Not signed in")
    if req.event not in ACTIVITY_EVENTS:
        raise HTTPException(status_code=400, detail="Unknown event")
    if req.video_id is not None:
        get_scoped_video(req.video_id, user_id)
    log_activity(
        req.event, session_id=req.session_id, user_id=user_id, video_id=req.video_id,
        position=req.position, watched=req.watched_seconds, detail=req.detail,
        ip=client_ip(request), device=request_device(request)
    )
    return {"success": True}

# Sessions with no lock event and nothing new for this long are shown as ended without a lock
ACTIVE_SESSION_MINUTES = 20

@app.get("/api/v1/activity", dependencies=[Depends(require_admin)])
async def list_activity(days: int = 7, user_id: Optional[int] = None):
    """
    Admin console: the TV sessions of the last `days` days, newest first. Each session runs from a
    code entry to its lock and carries a summary plus every event in order. Events outside a session
    (wrong codes, admin logins, code resets) are returned separately.
    """
    days = max(1, min(days, 366))
    since = (datetime.utcnow() - timedelta(days=days)).isoformat(timespec="seconds")
    rows = get_activity(since, user_id)

    sessions: Dict[str, List[Dict[str, Any]]] = {}
    other: List[Dict[str, Any]] = []
    for row in rows:
        if row["session_id"]:
            sessions.setdefault(row["session_id"], []).append(row)
        else:
            other.append(row)

    now = datetime.utcnow()
    result = []
    for session_id, events in sessions.items():
        first, last = events[0], events[-1]
        unlock = next((e for e in events if e["event"] == "unlock"), first)
        lock = next((e for e in reversed(events) if e["event"] == "lock"), None)
        last_ts = datetime.fromisoformat(last["ts"].rstrip("Z"))
        if lock:
            state = "locked"
        elif now - last_ts < timedelta(minutes=ACTIVE_SESSION_MINUTES):
            state = "active"
        else:
            state = "no_lock"
        uid = unlock["user_id"] if unlock["user_id"] is not None else first["user_id"]
        result.append({
            "session_id": session_id,
            "user_id": uid,
            "user_name": USERS.get(uid, {}).get("name", f"User {uid}") if uid is not None else "",
            "device": unlock["device"] or next((e["device"] for e in events if e["device"]), ""),
            "ip": unlock["ip"],
            "started_at": first["ts"],
            "ended_at": last["ts"],
            "state": state,
            "lock_reason": lock["detail"] if lock else "",
            "videos_played": len({e["video_id"] for e in events if e["event"] == "play"}),
            "watched_seconds": round(sum(e["watched"] or 0 for e in events if e["event"] == "exit")),
            "events": [
                {k: e[k] for k in ("ts", "event", "video_id", "video_title", "position", "watched", "detail")}
                for e in events
            ]
        })
    result.sort(key=lambda r: r["started_at"], reverse=True)
    other.reverse()
    return {
        "days": days,
        "sessions": result,
        "other_events": [
            {
                "ts": e["ts"], "event": e["event"], "detail": e["detail"], "ip": e["ip"], "device": e["device"],
                "user_name": USERS.get(e["user_id"], {}).get("name", "") if e["user_id"] is not None else ""
            }
            for e in other
        ]
    }

