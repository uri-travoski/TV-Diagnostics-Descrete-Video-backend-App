import subprocess
import json
import hashlib
from pathlib import Path
from typing import Dict, Any, Optional
from .config import THUMBNAILS_DIR

def run_ffprobe(filepath: str) -> Optional[Dict[str, Any]]:
    """Runs ffprobe on a video file and returns formatted metadata."""
    cmd = [
        "ffprobe",
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        filepath
    ]
    try:
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=30)
        if result.returncode != 0:
            return None
        return json.loads(result.stdout)
    except Exception as e:
        print(f"Error running ffprobe on {filepath}: {e}")
        return None

def extract_metadata(filepath: str) -> Dict[str, Any]:
    """Extracts duration, resolution, codecs from ffprobe output."""
    path_obj = Path(filepath)
    filename = path_obj.name
    title = path_obj.stem.replace(".", " ").replace("_", " ").strip()
    filesize = path_obj.stat().st_size if path_obj.exists() else 0
    
    probe = run_ffprobe(filepath)
    
    duration = 0.0
    width = 0
    height = 0
    video_codec = ""
    audio_codec = ""
    audio_channels = 2
    
    if probe:
        # Format info
        fmt = probe.get("format", {})
        if "duration" in fmt:
            try:
                duration = float(fmt["duration"])
            except ValueError:
                pass
                
        # Streams info
        for stream in probe.get("streams", []):
            codec_type = stream.get("codec_type")
            if codec_type == "video" and not video_codec:
                video_codec = stream.get("codec_name", "")
                width = int(stream.get("width", 0))
                height = int(stream.get("height", 0))
                if duration == 0.0 and "duration" in stream:
                    try:
                        duration = float(stream["duration"])
                    except ValueError:
                        pass
            elif codec_type == "audio" and not audio_codec:
                audio_codec = stream.get("codec_name", "")
                audio_channels = int(stream.get("channels", 2))

    return {
        "filepath": str(path_obj.resolve()),
        "filename": filename,
        "title": title,
        "filesize": filesize,
        "duration": round(duration, 2),
        "width": width,
        "height": height,
        "video_codec": video_codec,
        "audio_codec": audio_codec,
        "audio_channels": audio_channels
    }

def generate_thumbnail(filepath: str, duration: float) -> str:
    """
    Generates a JPEG thumbnail photo exactly 60 seconds into the video.
    If the video is shorter than 60 seconds, it uses mid-point (duration / 2).
    Returns the path to the thumbnail or empty string on failure.
    """
    path_obj = Path(filepath)
    if not path_obj.exists():
        return ""
        
    # Unique thumbnail filename based on file path hash
    hash_digest = hashlib.md5(str(path_obj.resolve()).encode("utf-8")).hexdigest()
    thumb_path = THUMBNAILS_DIR / f"{hash_digest}.jpg"
    
    # If thumbnail already exists and is not empty, return it
    if thumb_path.exists() and thumb_path.stat().st_size > 0:
        return str(thumb_path)
        
    # Calculate capture timestamp: 60s if video is >= 70s, else halfway, or 1s
    if duration >= 65.0:
        seek_time = "00:01:00"
    elif duration > 2.0:
        midpoint = int(duration / 2)
        m, s = divmod(midpoint, 60)
        h, m = divmod(m, 60)
        seek_time = f"{h:02d}:{m:02d}:{s:02d}"
    else:
        seek_time = "00:00:01"
        
    cmd = [
        "ffmpeg",
        "-ss", seek_time,
        "-i", str(path_obj.resolve()),
        "-vframes", "1",
        "-q:v", "2",
        "-vf", "scale='min(640,iw)':-2",  # Scale smoothly to max 640px wide for fast loading on TV
        "-y",
        str(thumb_path)
    ]
    
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
        if res.returncode == 0 and thumb_path.exists() and thumb_path.stat().st_size > 0:
            return str(thumb_path)
        else:
            # Fallback to 00:00:01 if 60s failed
            fallback_cmd = [
                "ffmpeg",
                "-ss", "00:00:01",
                "-i", str(path_obj.resolve()),
                "-vframes", "1",
                "-q:v", "2",
                "-vf", "scale='min(640,iw)':-2",
                "-y",
                str(thumb_path)
            ]
            res_fb = subprocess.run(fallback_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
            if res_fb.returncode == 0 and thumb_path.exists() and thumb_path.stat().st_size > 0:
                return str(thumb_path)
    except Exception as e:
        print(f"Error generating thumbnail for {filepath}: {e}")
        
    return ""

def preview_dir(filepath: str) -> Path:
    from .config import PREVIEWS_DIR
    return PREVIEWS_DIR / hashlib.md5(str(Path(filepath).resolve()).encode("utf-8")).hexdigest()

PREVIEWS_COMPLETE_MARKER = ".complete"

def previews_complete(filepath: str) -> bool:
    return (preview_dir(filepath) / PREVIEWS_COMPLETE_MARKER).exists()

def generate_all_preview_frames(filepath: str, duration: float) -> bool:
    """
    Builds every storyboard frame of a video in one low-priority ffmpeg pass (keyframes only,
    so it is light on CPU), for the background pre-generation after scans. Frames that already
    exist (made on demand) are kept. Returns True when the set is complete.
    """
    import os
    import shutil
    import tempfile
    from .config import PREVIEW_INTERVAL_SECONDS

    path_obj = Path(filepath)
    if not path_obj.exists() or duration <= PREVIEW_INTERVAL_SECONDS:
        return False
    frame_dir = preview_dir(filepath)
    frame_dir.mkdir(parents=True, exist_ok=True)
    expected = int((duration - 0.5) // PREVIEW_INTERVAL_SECONDS)

    tmp = Path(tempfile.mkdtemp(prefix="pv_", dir=str(frame_dir.parent)))
    try:
        cmd = ["nice", "-n", "19"] if shutil.which("nice") else []
        cmd += [
            "ffmpeg", "-v", "error",
            "-skip_frame", "nokey",
            "-i", str(path_obj.resolve()),
            "-an", "-sn",
            "-vf", f"fps=1/{PREVIEW_INTERVAL_SECONDS},scale='min(480,iw)':-2",
            "-q:v", "4",
            "-start_number", "0",
            "-y", str(tmp / "%d.jpg")
        ]
        # Generous timeout: the whole file is read once (about a minute per hour of HD video)
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             timeout=max(300, int(duration)))
        if res.returncode != 0:
            print(f"[Previews] ffmpeg failed for {filepath}: {res.stderr.decode(errors='ignore')[-300:]}")
            return False
        # Output frame n is the picture at n * interval; storyboard index i is at (i + 1) * interval
        for frame in tmp.glob("*.jpg"):
            n = int(frame.stem)
            target = frame_dir / f"{n - 1}.jpg"
            if n >= 1 and n - 1 < expected and not target.exists() and frame.stat().st_size > 0:
                os.replace(frame, target)
        # The pass can stop short of the last frames near the end: seek for any that are missing
        for index in range(expected):
            if not (frame_dir / f"{index}.jpg").exists():
                generate_preview_frame(filepath, duration, index)
        (frame_dir / PREVIEWS_COMPLETE_MARKER).write_text(str(expected))
        return True
    except Exception as e:
        print(f"[Previews] Could not build previews for {filepath}: {e}")
        return False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def generate_preview_frame(filepath: str, duration: float, index: int) -> str:
    """
    Storyboard frame `index` for the TV grid preview: the picture at (index + 1) * PREVIEW_INTERVAL_SECONDS.
    Generated on first request and cached under data/previews/<path hash>/<index>.jpg.
    Returns the path, or empty string if the frame is past the end or ffmpeg failed.
    """
    from .config import PREVIEW_INTERVAL_SECONDS

    path_obj = Path(filepath)
    seek = (index + 1) * PREVIEW_INTERVAL_SECONDS
    if index < 0 or (duration > 0 and seek >= duration):
        return ""

    frame_dir = preview_dir(filepath)
    frame_path = frame_dir / f"{index}.jpg"
    if frame_path.exists() and frame_path.stat().st_size > 0:
        return str(frame_path)
    if not path_obj.exists():
        return ""

    frame_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg",
        "-ss", str(seek),
        "-i", str(path_obj.resolve()),
        "-vframes", "1",
        "-q:v", "4",
        "-vf", "scale='min(480,iw)':-2",
        "-y",
        str(frame_path)
    ]
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        if res.returncode == 0 and frame_path.exists() and frame_path.stat().st_size > 0:
            return str(frame_path)
    except Exception as e:
        print(f"Error generating preview frame {index} for {filepath}: {e}")
    return ""
