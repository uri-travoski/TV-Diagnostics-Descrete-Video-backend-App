import os
import mimetypes
from pathlib import Path
from typing import Generator, Tuple, Optional
from fastapi import Request, HTTPException, status
from fastapi.responses import StreamingResponse, Response

CHUNK_SIZE = 1024 * 1024  # 1MB chunks

def parse_range_header(range_header: str, file_size: int) -> Tuple[int, int]:
    """Parses standard Range header: bytes=start-end."""
    try:
        units, range_val = range_header.strip().split("=")
        if units.strip().lower() != "bytes":
            raise ValueError()
        
        parts = range_val.split("-")
        start_str = parts[0].strip()
        end_str = parts[1].strip() if len(parts) > 1 else ""
        
        if start_str and end_str:
            start = int(start_str)
            end = int(end_str)
        elif start_str:
            start = int(start_str)
            end = file_size - 1
        elif end_str:
            start = file_size - int(end_str)
            end = file_size - 1
        else:
            start = 0
            end = file_size - 1
            
        start = max(0, start)
        end = min(file_size - 1, end)
        
        if start > end or start >= file_size:
            raise ValueError()
            
        return start, end
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE,
            headers={"Content-Range": f"bytes */{file_size}"}
        )

def file_chunk_generator(filepath: str, start: int, end: int, chunk_size: int = CHUNK_SIZE) -> Generator[bytes, None, None]:
    """Yields chunks of a file between start and end bytes."""
    with open(filepath, "rb") as f:
        f.seek(start)
        bytes_left = end - start + 1
        while bytes_left > 0:
            read_bytes = min(chunk_size, bytes_left)
            data = f.read(read_bytes)
            if not data:
                break
            bytes_left -= len(data)
            yield data

def get_media_response(filepath: str, request: Request) -> Response:
    """
    Returns a FastAPI StreamingResponse supporting HTTP 206 Partial Content
    for direct hardware-accelerated streaming on Android TV (ExoPlayer).
    """
    path_obj = Path(filepath)
    if not path_obj.exists() or not path_obj.is_file():
        raise HTTPException(status_code=404, detail="Video file not found on disk")
        
    file_size = path_obj.stat().st_size
    mime_type, _ = mimetypes.guess_type(filepath)
    if not mime_type:
        mime_type = "video/mp4"

    range_header = request.headers.get("range") or request.headers.get("Range")
    
    headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": mime_type,
    }

    if range_header:
        start, end = parse_range_header(range_header, file_size)
        content_length = end - start + 1
        headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
        headers["Content-Length"] = str(content_length)
        
        return StreamingResponse(
            file_chunk_generator(str(path_obj), start, end),
            status_code=status.HTTP_206_PARTIAL_CONTENT,
            headers=headers,
            media_type=mime_type
        )
    else:
        headers["Content-Length"] = str(file_size)
        return StreamingResponse(
            file_chunk_generator(str(path_obj), 0, file_size - 1),
            status_code=status.HTTP_200_OK,
            headers=headers,
            media_type=mime_type
        )
