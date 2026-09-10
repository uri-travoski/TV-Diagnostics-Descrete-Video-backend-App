let allVideos = [];
let allTags = [];
let currentRatingFilter = null;
let currentTagFilter = "";
let currentSearch = "";
let currentSort = "title_asc";
let modalSelectedRating = 0;

let currentPlayingVideoId = null;
let lastProgressSyncTime = 0;

let wasWaitingForMount = false;
let mountPollTimer = null;

document.addEventListener("DOMContentLoaded", () => {
    loadVideos();
    loadTags();
    checkMountStatus();
    // Periodic status heartbeat
    setInterval(checkMountStatus, 10000);
});

async function checkMountStatus() {
    try {
        const res = await fetch("/api/v1/status");
        if (!res.ok) return;
        const data = await res.json();
        
        const badge = document.getElementById("storageBadge");
        const notice = document.getElementById("smbMountNotice");

        if (data.mount_status === "waiting_for_mount") {
            badge.className = "badge badge-waiting";
            badge.textContent = "🟡 Storage: Waiting for SMB Mount...";
            notice.classList.remove("hidden");
            wasWaitingForMount = true;
            
            // Poll faster while waiting
            if (!mountPollTimer) {
                mountPollTimer = setTimeout(() => {
                    mountPollTimer = null;
                    checkMountStatus();
                }, 4000);
            }
        } else {
            badge.className = "badge badge-ready";
            badge.textContent = "🟢 Storage: Mounted & Ready";
            notice.classList.add("hidden");
            
            if (wasWaitingForMount) {
                wasWaitingForMount = false;
                showAlert("SMB storage mount detected! Refreshing library...", "success");
                await loadVideos();
                await loadTags();
            }
        }
    } catch (e) {
        console.error("Status check failed", e);
    }
}

function showAlert(message, type = "info") {
    const alert = document.getElementById("statusAlert");
    alert.className = `status-alert ${type}`;
    alert.textContent = message;
    alert.classList.remove("hidden");
    setTimeout(() => {
        alert.classList.add("hidden");
    }, 4000);
}

function formatDuration(seconds) {
    if (!seconds || seconds <= 0) return "00:00";
    const sec = Math.floor(seconds);
    const hrs = Math.floor(sec / 3600);
    const mins = Math.floor((sec % 3600) / 60);
    const remainingSec = sec % 60;
    
    if (hrs > 0) {
        return `${hrs}:${mins.toString().padStart(2, '0')}:${remainingSec.toString().padStart(2, '0')}`;
    }
    return `${mins}:${remainingSec.toString().padStart(2, '0')}`;
}

async function loadTags() {
    try {
        const res = await fetch("/api/v1/tags");
        if (!res.ok) return;
        const data = await res.json();
        allTags = data.map(item => typeof item === "string" ? item : (item.tag || "")).filter(Boolean);
        
        const select = document.getElementById("tagSelect");
        if (!select) return;

        const currentVal = select.value;
        select.innerHTML = `<option value="">All Tags</option>` + 
            allTags.map(tag => `<option value="${escapeHtml(tag)}"${tag === currentVal ? " selected" : ""}>${escapeHtml(tag)}</option>`).join("");
    } catch (e) {
        console.error("Failed to load tags", e);
    }
}

async function loadVideos() {
    try {
        const res = await fetch("/api/v1/videos");
        if (!res.ok) throw new Error("Failed to load videos");
        allVideos = await res.json();
        updateCounts();
        renderGrid();
    } catch (e) {
        showAlert("Error loading video library: " + e.message, "error");
        document.getElementById("videoGrid").innerHTML = `<div class="empty-state">Error loading videos. Check backend logs.</div>`;
    }
}

function updateCounts() {
    const all = allVideos.length;
    const unrated = allVideos.filter(v => v.rating === 0).length;
    const one = allVideos.filter(v => v.rating === 1).length;
    const two = allVideos.filter(v => v.rating === 2).length;

    document.getElementById("countAll").textContent = all;
    document.getElementById("countUnrated").textContent = unrated;
    document.getElementById("countOne").textContent = one;
    document.getElementById("countTwo").textContent = two;
}

function setRatingFilter(rating, btn) {
    currentRatingFilter = rating;
    document.querySelectorAll(".filter-pills .pill").forEach(p => p.classList.remove("active"));
    btn.classList.add("active");
    renderGrid();
}

function handleTagSelect(tag) {
    currentTagFilter = tag ? tag.trim().toLowerCase() : "";
    renderGrid();
}

function filterByTag(tag) {
    const select = document.getElementById("tagSelect");
    if (select) {
        select.value = tag;
        handleTagSelect(tag);
    }
}

function handleSearch() {
    currentSearch = document.getElementById("searchInput").value.trim().toLowerCase();
    renderGrid();
}

function handleSortSelect(val) {
    currentSort = val;
    renderGrid();
}

function renderGrid() {
    const grid = document.getElementById("videoGrid");
    
    let filtered = allVideos.filter(v => {
        if (currentRatingFilter !== null && v.rating !== currentRatingFilter) {
            return false;
        }
        if (currentTagFilter) {
            const videoTags = (v.tags || "").toLowerCase().split(",").map(t => t.trim());
            if (!videoTags.includes(currentTagFilter)) {
                return false;
            }
        }
        if (currentSearch) {
            const matchTitle = (v.title || "").toLowerCase().includes(currentSearch);
            const matchFile = (v.filename || "").toLowerCase().includes(currentSearch);
            const matchNotes = (v.notes || "").toLowerCase().includes(currentSearch);
            const matchTags = (v.tags || "").toLowerCase().includes(currentSearch);
            return matchTitle || matchFile || matchNotes || matchTags;
        }
        return true;
    });

    filtered.sort((a, b) => {
        switch (currentSort) {
            case "title_asc":
                return (a.title || a.filename).localeCompare(b.title || b.filename);
            case "title_desc":
                return (b.title || b.filename).localeCompare(a.title || a.filename);
            case "rating_desc":
                return (b.rating || 0) - (a.rating || 0);
            case "rating_asc":
                return (a.rating || 0) - (b.rating || 0);
            case "recent_desc":
                return (new Date(b.created_at || 0) - new Date(a.created_at || 0)) || (b.id - a.id);
            case "recent_asc":
                return (new Date(a.created_at || 0) - new Date(b.created_at || 0)) || (a.id - b.id);
            case "last_watched":
                return new Date(b.last_watched_at || 0) - new Date(a.last_watched_at || 0);
            case "duration_desc":
                return (b.duration || 0) - (a.duration || 0);
            case "duration_asc":
                return (a.duration || 0) - (b.duration || 0);
            case "progress_desc":
                const pctA = a.duration > 0 ? (a.watched_seconds / a.duration) : 0;
                const pctB = b.duration > 0 ? (b.watched_seconds / b.duration) : 0;
                return pctB - pctA;
            default:
                return 0;
        }
    });

    if (filtered.length === 0) {
        grid.innerHTML = `<div class="empty-state" style="grid-column: 1/-1; text-align: center; padding: 40px; color: var(--text-muted);">
            No videos found matching the current filter.
        </div>`;
        return;
    }

    grid.innerHTML = filtered.map(v => {
        const progressPct = v.duration > 0 ? Math.min(100, Math.round((v.watched_seconds / v.duration) * 100)) : 0;
        const resBadge = v.height >= 2160 ? "4K" : v.height >= 1080 ? "1080p" : v.height >= 720 ? "720p" : (v.height > 0 ? `${v.height}p` : "SD");
        const hasThumb = v.thumbnail_path && v.thumbnail_path.length > 0;
        
        // Discreet Circle Rating symbols: 0 = '○', 1 = '●', 2 = '●●'
        const circleSymbol = v.rating === 1 ? "●" : v.rating === 2 ? "●●" : "○";
        const isRated = v.rating > 0;

        const tagList = (v.tags || "").split(",").map(t => t.trim()).filter(t => t.length > 0);
        const tagsHtml = tagList.length > 0 
            ? tagList.map(t => `<span class="tag-badge" onclick="event.stopPropagation(); filterByTag('${escapeHtml(t)}')">${escapeHtml(t)}</span>`).join("")
            : "";

        const notesText = (v.notes || "").trim();
        const hasNotes = notesText.length > 0;

        return `
            <div class="video-card" id="card-${v.id}">
                <div class="thumb-container" onclick="openPreview(${v.id}, '${escapeHtml(v.title)}')">
                    ${hasThumb 
                        ? `<img src="/api/v1/videos/${v.id}/thumbnail" alt="${escapeHtml(v.title)}" class="thumb-img" loading="lazy">`
                        : `<div class="thumb-placeholder">▶</div>`
                    }
                    <span class="res-badge">${resBadge}</span>
                    <span class="duration-badge">${formatDuration(v.duration)}</span>
                </div>
                
                <div class="progress-bar-wrap">
                    <div class="progress-bar-fill" style="width: ${progressPct}%;"></div>
                </div>

                <div class="card-body">
                    <h4 class="video-title" title="${escapeHtml(v.filename)}">${escapeHtml(v.title)}</h4>

                    <div class="card-meta-row">
                        <span>${progressPct > 0 ? `${formatDuration(v.watched_seconds)} (${progressPct}%)` : 'Unplayed'}</span>
                        <button class="circle-symbol-btn ${isRated ? 'rated' : 'unrated'}" 
                                onclick="cycleRating(${v.id}, ${v.rating})" 
                                title="Click to cycle rating (○ -> ● -> ●●)">
                            ${circleSymbol}
                        </button>
                    </div>

                    ${tagList.length > 0 ? `<div class="tags-row">${tagsHtml}</div>` : ''}

                    <div class="notes-preview-wrap">
                        <span class="notes-preview-label">Notes (click to edit):</span>
                        <div class="notes-preview ${hasNotes ? '' : 'empty'}" 
                             onclick="openEditModal(${v.id})" 
                             title="Click to edit full notes and tags">
                            ${hasNotes ? escapeHtml(notesText) : 'No notes added. Click to edit...'}
                        </div>
                    </div>

                    <div class="card-actions">
                        <button class="action-btn-sm" onclick="resetVideoProgress(${v.id})">↺ Reset</button>
                        <button class="action-btn-sm" onclick="openEditModal(${v.id})">✎ Edit</button>
                        <a href="/api/v1/videos/${v.id}/stream" target="_blank" class="action-btn-sm">Direct Stream</a>
                    </div>
                </div>
            </div>
        `;
    }).join("");
}

function escapeHtml(text) {
    if (!text) return "";
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
}

/* Edit Modal Logic */
function openEditModal(videoId) {
    const video = allVideos.find(v => v.id === videoId);
    if (!video) return;

    document.getElementById("editVideoId").value = video.id;
    document.getElementById("editModalTitle").textContent = `Edit: ${video.title}`;
    document.getElementById("editNotes").value = video.notes || "";
    document.getElementById("editTags").value = video.tags || "";

    selectModalRating(video.rating || 0);

    // Render tag chips preview
    const tagsDiv = document.getElementById("modalExistingTags");
    const tagList = (video.tags || "").split(",").map(t => t.trim()).filter(t => t.length > 0);
    tagsDiv.innerHTML = tagList.map(t => `<span class="tag-badge">${escapeHtml(t)}</span>`).join("");

    document.getElementById("editModal").classList.remove("hidden");
}

function closeEditModal(e) {
    if (e && e.target && e.target.id !== "editModal" && !e.target.classList.contains("modal-close")) return;
    document.getElementById("editModal").classList.add("hidden");
}

function selectModalRating(rating) {
    modalSelectedRating = rating;
    document.querySelectorAll(".rating-pick-btn").forEach(btn => {
        const r = parseInt(btn.dataset.rating, 10);
        if (r === rating) {
            btn.classList.add("selected");
        } else {
            btn.classList.remove("selected");
        }
    });
}

async function saveEditModal() {
    const videoId = parseInt(document.getElementById("editVideoId").value, 10);
    if (!videoId) return;

    const notes = document.getElementById("editNotes").value;
    const tags = document.getElementById("editTags").value;
    const rating = modalSelectedRating;

    try {
        const res = await fetch(`/api/v1/videos/${videoId}/metadata`, {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                notes: notes,
                tags: tags,
                rating: rating
            })
        });

        if (res.ok) {
            const v = allVideos.find(item => item.id === videoId);
            if (v) {
                v.notes = notes;
                v.tags = tags;
                v.rating = rating;
            }
            updateCounts();
            renderGrid();
            await loadTags();
            document.getElementById("editModal").classList.add("hidden");
            showAlert("Video details saved successfully", "success");
        } else {
            showAlert("Failed to save video details", "error");
        }
    } catch (e) {
        showAlert("Save error: " + e.message, "error");
    }
}

async function cycleRating(videoId, currentRating) {
    // Cycle: 0 -> 1 -> 2 -> 0
    const nextRating = (currentRating + 1) % 3;
    try {
        const res = await fetch(`/api/v1/videos/${videoId}/metadata`, {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({ rating: nextRating })
        });
        if (res.ok) {
            const v = allVideos.find(item => item.id === videoId);
            if (v) v.rating = nextRating;
            updateCounts();
            renderGrid();
        }
    } catch (e) {
        showAlert("Failed to update rating", "error");
    }
}

async function resetVideoProgress(videoId) {
    try {
        const res = await fetch(`/api/v1/videos/${videoId}/reset-progress`, { method: "POST" });
        if (res.ok) {
            const v = allVideos.find(item => item.id === videoId);
            if (v) {
                v.watched_seconds = 0;
                v.completed = 0;
            }
            renderGrid();
            showAlert("Playback position reset to 0:00", "success");
        }
    } catch (e) {
        showAlert("Failed to reset progress", "error");
    }
}

async function resetAllPlayback() {
    if (!confirm("Are you sure you want to reset playback position for ALL videos?")) return;
    try {
        const res = await fetch("/api/v1/videos/reset-all-progress", { method: "POST" });
        if (res.ok) {
            allVideos.forEach(v => {
                v.watched_seconds = 0;
                v.completed = 0;
            });
            renderGrid();
            showAlert("All video progress reset", "success");
        }
    } catch (e) {
        showAlert("Failed to reset all progress", "error");
    }
}

async function triggerScan() {
    const btn = document.getElementById("btnScan");
    btn.disabled = true;
    btn.innerHTML = `<span class="btn-icon">↻</span> Scanning...`;
    showAlert("Scanning media folders and generating thumbnails (60s)...", "info");

    try {
        const res = await fetch("/api/v1/scan", { method: "POST" });
        const data = await res.json();
        showAlert(`Scan completed: ${data.indexed_count || 0} indexed, ${data.pruned_count || 0} removed`, "success");
        await loadVideos();
        await loadTags();
    } catch (e) {
        showAlert("Scan error: " + e.message, "error");
    } finally {
        btn.disabled = false;
        btn.innerHTML = `<span class="btn-icon">↻</span> Rescan Media`;
    }
}

function openPreview(videoId, title) {
    const modal = document.getElementById("videoModal");
    const video = document.getElementById("playerPreview");
    currentPlayingVideoId = videoId;

    const v = allVideos.find(x => x.id === videoId);
    const resumeTime = (v && v.watched_seconds > 5 && v.duration > 0 && (v.watched_seconds / v.duration) < 0.95) ? v.watched_seconds : 0;

    document.getElementById("modalTitle").textContent = title + (resumeTime > 0 ? ` (Resuming at ${formatDuration(resumeTime)})` : "");
    video.src = `/api/v1/videos/${videoId}/stream`;
    modal.classList.remove("hidden");

    const onLoadedMetadata = () => {
        if (resumeTime > 0) {
            video.currentTime = resumeTime;
        }
        video.removeEventListener("loadedmetadata", onLoadedMetadata);
    };
    video.addEventListener("loadedmetadata", onLoadedMetadata);

    video.ontimeupdate = handleVideoTimeUpdate;
    video.onpause = () => syncCurrentPlaybackProgress(false);
    video.onended = () => syncCurrentPlaybackProgress(true);

    video.play().catch(() => {});
}

function handleVideoTimeUpdate() {
    const now = Date.now();
    if (now - lastProgressSyncTime > 5000) { // Sync every 5 seconds
        lastProgressSyncTime = now;
        syncCurrentPlaybackProgress(false);
    }
}

async function syncCurrentPlaybackProgress(completed = false) {
    if (!currentPlayingVideoId) return;
    const video = document.getElementById("playerPreview");
    if (!video || isNaN(video.currentTime)) return;

    const curSec = Math.round(video.currentTime);
    const v = allVideos.find(x => x.id === currentPlayingVideoId);
    if (v) {
        v.watched_seconds = curSec;
        if (completed) v.completed = 1;
        updateCardProgress(v);
    }

    try {
        await fetch(`/api/v1/videos/${currentPlayingVideoId}/progress`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                watched_seconds: curSec,
                completed: completed
            })
        });
    } catch (e) {
        console.error("Failed to sync video progress", e);
    }
}

function updateCardProgress(v) {
    const card = document.getElementById(`card-${v.id}`);
    if (!card) return;
    const progressPct = v.duration > 0 ? Math.min(100, Math.round((v.watched_seconds / v.duration) * 100)) : 0;
    const progBar = card.querySelector(".progress-fill");
    if (progBar) progBar.style.width = `${progressPct}%`;
    const timeLabel = card.querySelector(".time-label");
    if (timeLabel) timeLabel.textContent = `${formatDuration(v.watched_seconds)} / ${formatDuration(v.duration)}`;
}

function closeModal(e) {
    if (e && e.target && e.target.id !== "videoModal" && !e.target.classList.contains("modal-close")) return;
    const modal = document.getElementById("videoModal");
    const video = document.getElementById("playerPreview");

    syncCurrentPlaybackProgress(false);
    video.pause();
    video.ontimeupdate = null;
    video.onpause = null;
    video.onended = null;
    video.src = "";
    currentPlayingVideoId = null;
    modal.classList.add("hidden");
}
