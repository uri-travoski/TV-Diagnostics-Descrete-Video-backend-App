import sqlite3
import shutil
import os
from pathlib import Path
from datetime import datetime
from typing import Optional, List, Dict, Any
from .config import DB_PATH, BACKUP_DIR

def get_connection() -> sqlite3.Connection:
    conn = sqlite3.connect(str(DB_PATH), timeout=15.0)
    conn.row_factory = sqlite3.Row
    # WAL (Write-Ahead Logging) enables non-blocking concurrent readers while a writer writes.
    # busy_timeout ensures operations wait up to 5000ms instead of immediately failing with "database is locked".
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn

def create_database_backup(max_backups: int = 10) -> Optional[str]:
    """
    Creates a safe, non-blocking online snapshot backup of the SQLite database
    using SQLite's native backup API. Keeps the latest `max_backups` copies.
    """
    if not DB_PATH.exists():
        return None

    try:
        BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_file = BACKUP_DIR / f"tv_diagnostics_backup_{timestamp}.db"

        # Safe online backup while DB is running and accessible
        src_conn = sqlite3.connect(str(DB_PATH), timeout=15.0)
        dest_conn = sqlite3.connect(str(backup_file))
        src_conn.backup(dest_conn)
        dest_conn.close()
        src_conn.close()

        # Prune older backups beyond max_backups
        existing_backups = sorted(BACKUP_DIR.glob("tv_diagnostics_backup_*.db"), key=os.path.getmtime)
        if len(existing_backups) > max_backups:
            for old_backup in existing_backups[:-max_backups]:
                try:
                    old_backup.unlink()
                except Exception:
                    pass

        print(f"[Backup] Created hot database backup: {backup_file.name}")
        return str(backup_file)
    except Exception as e:
        print(f"[Backup Error] Failed to create database backup: {e}")
        return None

def verify_and_restore_integrity() -> bool:
    """
    Checks database integrity using SQLite PRAGMA integrity_check.
    If corruption is detected, automatically restores from the latest healthy backup.
    """
    if not DB_PATH.exists():
        return True

    is_corrupt = False
    try:
        conn = sqlite3.connect(str(DB_PATH), timeout=10.0)
        cursor = conn.cursor()
        cursor.execute("PRAGMA integrity_check")
        rows = cursor.fetchall()
        conn.close()

        if not rows or rows[0][0] != "ok":
            print(f"[Integrity Check] Database corruption detected! Results: {rows}")
            is_corrupt = True
    except sqlite3.DatabaseError as db_err:
        print(f"[Integrity Check] Database error: {db_err}")
        is_corrupt = True
    except Exception as ex:
        print(f"[Integrity Check] Unexpected check error: {ex}")
        is_corrupt = True

    if not is_corrupt:
        return True

    # Attempt automatic recovery from latest healthy backup
    print("[Recovery] Attempting automatic restoration from latest backup...")
    backups = sorted(BACKUP_DIR.glob("tv_diagnostics_backup_*.db"), key=os.path.getmtime, reverse=True)
    for backup in backups:
        try:
            test_conn = sqlite3.connect(str(backup))
            test_cur = test_conn.cursor()
            test_cur.execute("PRAGMA integrity_check")
            test_rows = test_cur.fetchall()
            test_conn.close()

            if test_rows and test_rows[0][0] == "ok":
                # Quarantine corrupt db
                corrupt_backup = DB_PATH.with_name(f"tv_diagnostics_corrupt_{datetime.now().strftime('%Y%m%d_%H%M%S')}.db")
                shutil.copy2(DB_PATH, corrupt_backup)
                shutil.copy2(backup, DB_PATH)
                print(f"[Recovery SUCCESS] Restored database from healthy backup '{backup.name}'. Corrupt database preserved as '{corrupt_backup.name}'.")
                return True
        except Exception as rec_err:
            print(f"[Recovery] Backup '{backup.name}' could not be used: {rec_err}")

    print("[Recovery ERROR] No valid backup found for automatic restoration!")
    return False

def init_db():
    verify_and_restore_integrity()
    
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS videos (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            filepath TEXT UNIQUE NOT NULL,
            filename TEXT NOT NULL,
            title TEXT NOT NULL,
            filesize INTEGER NOT NULL DEFAULT 0,
            duration REAL NOT NULL DEFAULT 0.0,
            width INTEGER NOT NULL DEFAULT 0,
            height INTEGER NOT NULL DEFAULT 0,
            video_codec TEXT NOT NULL DEFAULT '',
            audio_codec TEXT NOT NULL DEFAULT '',
            audio_channels INTEGER NOT NULL DEFAULT 2,
            thumbnail_path TEXT NOT NULL DEFAULT '',
            rating INTEGER NOT NULL DEFAULT 0,
            notes TEXT NOT NULL DEFAULT '',
            tags TEXT NOT NULL DEFAULT '',
            watched_seconds REAL NOT NULL DEFAULT 0.0,
            completed INTEGER NOT NULL DEFAULT 0,
            last_watched_at TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
    """)
    # Migration check for existing databases
    cursor.execute("PRAGMA table_info(videos)")
    col_names = [col[1] for col in cursor.fetchall()]
    if "tags" not in col_names:
        cursor.execute("ALTER TABLE videos ADD COLUMN tags TEXT NOT NULL DEFAULT ''")

    cursor.execute("CREATE INDEX IF NOT EXISTS idx_videos_filepath ON videos(filepath)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_videos_rating ON videos(rating)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_videos_tags ON videos(tags)")
    conn.commit()
    conn.close()

    # Create baseline snapshot backup on startup
    create_database_backup()

def find_relocated_video(filename: str, filesize: int, duration: float) -> Optional[Dict[str, Any]]:
    """
    Checks if a video with the same filename, size, and duration exists
    at an old path that is no longer accessible on disk (i.e. file was moved/renamed directory).
    """
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT * FROM videos
        WHERE filename = ? AND filesize = ? AND ABS(duration - ?) < 1.0
    """, (filename, filesize, duration))
    rows = cursor.fetchall()
    conn.close()

    for row in rows:
        old_path = row["filepath"]
        if not os.path.exists(old_path):
            return dict(row)
    return None

def upsert_video(video_info: Dict[str, Any]) -> int:
    conn = get_connection()
    cursor = conn.cursor()
    now = datetime.utcnow().isoformat()
    new_filepath = video_info["filepath"]

    # Check if exact filepath already exists
    cursor.execute("SELECT id FROM videos WHERE filepath = ?", (new_filepath,))
    existing_row = cursor.fetchone()

    if existing_row:
        # Standard update for existing file
        video_id = existing_row["id"]
        cursor.execute("""
            UPDATE videos SET
                filename = :filename,
                title = :title,
                filesize = :filesize,
                duration = :duration,
                width = :width,
                height = :height,
                video_codec = :video_codec,
                audio_codec = :audio_codec,
                audio_channels = :audio_channels,
                thumbnail_path = CASE 
                    WHEN :thumbnail_path != '' THEN :thumbnail_path 
                    ELSE thumbnail_path 
                END,
                updated_at = :updated_at
            WHERE id = :id
        """, {
            **video_info,
            "id": video_id,
            "updated_at": now
        })
        conn.commit()
        conn.close()
        return video_id

    # Check if this file was moved/relocated from an older, missing path
    relocated = find_relocated_video(
        video_info["filename"],
        video_info["filesize"],
        video_info["duration"]
    )

    if relocated:
        # RECONNECT RELOCATED FILE: Keep notes, ratings, tags, watch progress, and thumbnails!
        video_id = relocated["id"]
        print(f"[Database] Reconnecting relocated video ID {video_id} ('{video_info['filename']}'): '{relocated['filepath']}' -> '{new_filepath}'")
        
        # Reuse thumbnail if existing thumbnail is valid
        thumb = video_info.get("thumbnail_path", "")
        if not thumb or not os.path.exists(thumb):
            thumb = relocated.get("thumbnail_path", "")

        cursor.execute("""
            UPDATE videos SET
                filepath = :filepath,
                filename = :filename,
                title = :title,
                filesize = :filesize,
                duration = :duration,
                width = :width,
                height = :height,
                video_codec = :video_codec,
                audio_codec = :audio_codec,
                audio_channels = :audio_channels,
                thumbnail_path = :thumbnail_path,
                updated_at = :updated_at
            WHERE id = :id
        """, {
            **video_info,
            "thumbnail_path": thumb,
            "id": video_id,
            "updated_at": now
        })
        conn.commit()
        conn.close()
        return video_id

    # Brand new video insertion
    cursor.execute("""
        INSERT INTO videos (
            filepath, filename, title, filesize, duration, width, height,
            video_codec, audio_codec, audio_channels, thumbnail_path,
            created_at, updated_at
        ) VALUES (
            :filepath, :filename, :title, :filesize, :duration, :width, :height,
            :video_codec, :audio_codec, :audio_channels, :thumbnail_path,
            :created_at, :updated_at
        )
    """, {
        **video_info,
        "created_at": now,
        "updated_at": now
    })
    
    video_id = cursor.lastrowid or 0
    conn.commit()
    conn.close()
    return video_id

def get_all_videos(
    search: Optional[str] = None,
    rating: Optional[int] = None,
    tag: Optional[str] = None,
    sort_by: str = "title"
) -> List[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    
    query = "SELECT * FROM videos WHERE 1=1"
    params: List[Any] = []
    
    if search:
        query += " AND (title LIKE ? OR filename LIKE ? OR notes LIKE ? OR tags LIKE ?)"
        wildcard = f"%{search}%"
        params.extend([wildcard, wildcard, wildcard, wildcard])
        
    if rating is not None and rating in (0, 1, 2):
        query += " AND rating = ?"
        params.append(rating)

    if tag and tag.strip():
        clean_tag = tag.strip().lower()
        query += " AND (lower(tags) = ? OR lower(tags) LIKE ? OR lower(tags) LIKE ? OR lower(tags) LIKE ?)"
        params.extend([clean_tag, f"{clean_tag},%", f"%, {clean_tag}%", f"%,{clean_tag}%"])
        
    sort_options = {
        "title": "title ASC",
        "title_desc": "title DESC",
        "recent": "created_at DESC",
        "last_watched": "last_watched_at DESC NULLS LAST",
        "duration": "duration DESC",
        "rating": "rating DESC"
    }
    order_clause = sort_options.get(sort_by, "title ASC")
    query += f" ORDER BY {order_clause}"
    
    cursor.execute(query, params)
    rows = cursor.fetchall()
    videos = [dict(row) for row in rows]
    conn.close()
    return videos

def get_video(video_id: int) -> Optional[Dict[str, Any]]:
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM videos WHERE id = ?", (video_id,))
    row = cursor.fetchone()
    conn.close()
    return dict(row) if row else None

def update_progress(video_id: int, watched_seconds: float, completed: Optional[bool] = None) -> bool:
    conn = get_connection()
    cursor = conn.cursor()
    now = datetime.utcnow().isoformat()
    
    # Check if marked completed
    if completed is None:
        cursor.execute("SELECT duration FROM videos WHERE id = ?", (video_id,))
        row = cursor.fetchone()
        if row and row["duration"] > 0 and (watched_seconds / row["duration"]) >= 0.92:
            completed = True
        else:
            completed = False

    cursor.execute("""
        UPDATE videos SET
            watched_seconds = ?,
            completed = ?,
            last_watched_at = ?,
            updated_at = ?
        WHERE id = ?
    """, (watched_seconds, 1 if completed else 0, now, now, video_id))
    
    success = cursor.rowcount > 0
    conn.commit()
    conn.close()
    return success

def reset_progress(video_id: int) -> bool:
    conn = get_connection()
    cursor = conn.cursor()
    now = datetime.utcnow().isoformat()
    cursor.execute("""
        UPDATE videos SET
            watched_seconds = 0.0,
            completed = 0,
            last_watched_at = NULL,
            updated_at = ?
        WHERE id = ?
    """, (now, video_id))
    success = cursor.rowcount > 0
    conn.commit()
    conn.close()
    return success

def reset_all_progress() -> int:
    conn = get_connection()
    cursor = conn.cursor()
    now = datetime.utcnow().isoformat()
    cursor.execute("""
        UPDATE videos SET
            watched_seconds = 0.0,
            completed = 0,
            last_watched_at = NULL,
            updated_at = ?
        WHERE id = ?
    """, (now,))
    count = cursor.rowcount
    conn.commit()
    conn.close()
    return count

def get_all_tags() -> List[Dict[str, Any]]:
    """Returns all unique tags with count of associated videos."""
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT tags FROM videos WHERE tags != ''")
    rows = cursor.fetchall()
    conn.close()
    
    tag_counts: Dict[str, int] = {}
    for row in rows:
        tags_str = row["tags"] if row["tags"] else ""
        for t in tags_str.split(","):
            clean = t.strip()
            if clean:
                tag_counts[clean] = tag_counts.get(clean, 0) + 1
                
    return [{"tag": k, "count": v} for k, v in sorted(tag_counts.items(), key=lambda x: (-x[1], x[0]))]

def update_metadata(
    video_id: int,
    notes: Optional[str] = None,
    rating: Optional[int] = None,
    tags: Optional[str] = None
) -> bool:
    conn = get_connection()
    cursor = conn.cursor()
    now = datetime.utcnow().isoformat()
    
    updates = []
    params: List[Any] = []
    
    if notes is not None:
        updates.append("notes = ?")
        params.append(notes)
    if rating is not None and rating in (0, 1, 2):
        updates.append("rating = ?")
        params.append(rating)
    if tags is not None:
        # Normalize tags (strip, clean commas, deduplicate while preserving order)
        clean_tags_list = [p.strip() for p in tags.split(",") if p.strip()]
        unique_tags = list(dict.fromkeys(clean_tags_list))
        cleaned_tags_str = ", ".join(unique_tags)
        updates.append("tags = ?")
        params.append(cleaned_tags_str)
        
    if not updates:
        conn.close()
        return False
        
    updates.append("updated_at = ?")
    params.append(now)
    params.append(video_id)
    
    query = f"UPDATE videos SET {', '.join(updates)} WHERE id = ?"
    cursor.execute(query, params)
    success = cursor.rowcount > 0
    conn.commit()
    conn.close()

    if success:
        # Trigger an automatic snapshot backup whenever user adds/edits ratings, tags, or notes
        create_database_backup()

    return success

def delete_missing_videos(existing_filepaths: set) -> int:
    from .config import ALLOW_PRUNE
    if not ALLOW_PRUNE:
        return 0

    if not existing_filepaths:
        print("[Database Safety] Discovered files is empty. Skipping pruning to protect unmounted SMB drive data.")
        return 0

    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT id, filepath FROM videos")
    rows = cursor.fetchall()
    
    deleted_count = 0
    for row in rows:
        if row["filepath"] not in existing_filepaths:
            cursor.execute("DELETE FROM videos WHERE id = ?", (row["id"],))
            deleted_count += 1
            
    conn.commit()
    conn.close()
    return deleted_count
