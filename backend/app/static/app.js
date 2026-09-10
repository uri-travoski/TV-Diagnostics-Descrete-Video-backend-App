let allVideos = [];
let allTags = [];
let currentRatingFilter = null;
let currentTagFilter = "";
let currentSearch = "";
let currentSort = "recent_desc";
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

    // Global listener to close tag dropdowns on click outside
    document.addEventListener("click", (e) => {
        const combobox = document.getElementById("tagCombobox");
        const tagMenu = document.getElementById("tagDropdownMenu");
        if (combobox && tagMenu && !combobox.contains(e.target)) {
            tagMenu.classList.add("hidden");
        }

        const editWrap = document.querySelector(".tag-autocomplete-wrap");
        const editMenu = document.getElementById("editTagsAutocomplete");
        if (editWrap && editMenu && !editWrap.contains(e.target)) {
            editMenu.classList.add("hidden");
        }
    });
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
        // Support {tag: string, count: number}
        allTags = data.map(item => {
            if (typeof item === "string") return { tag: item, count: 1 };
            return { tag: item.tag || "", count: item.count || 1 };
        }).filter(t => t.tag && t.tag.trim());
        
        // Render initial combobox menu if input exists
        const input = document.getElementById("tagComboboxInput");
        if (input && input.value.trim()) {
            renderTagComboboxMenu(input.value.trim());
        }
    } catch (e) {
        console.error("Failed to load tags", e);
    }
}

/* Tag Combobox Logic for Filter Bar (100+ tags support) */
function handleTagComboboxInput(val) {
    const clean = (val || "").trim();
    const clearBtn = document.getElementById("tagClearBtn");
    if (clearBtn) {
        if (clean.length > 0) clearBtn.classList.remove("hidden");
        else clearBtn.classList.add("hidden");
    }
    renderTagComboboxMenu(clean);
    // If input was completely emptied, reset filter
    if (clean === "" && currentTagFilter !== "") {
        currentTagFilter = "";
        renderGrid();
    }
}

function handleTagComboboxFocus() {
    const input = document.getElementById("tagComboboxInput");
    renderTagComboboxMenu(input ? input.value.trim() : "");
}

function renderTagComboboxMenu(query) {
    const menu = document.getElementById("tagDropdownMenu");
    if (!menu) return;

    let matches = allTags;
    if (query) {
        const qLower = query.toLowerCase();
        matches = allTags.filter(t => t.tag.toLowerCase().startsWith(qLower));
    }

    if (matches.length === 0) {
        menu.innerHTML = `<div class="tag-dropdown-empty">No tags starting with "${escapeHtml(query)}"</div>`;
        menu.classList.remove("hidden");
        return;
    }

    menu.innerHTML = matches.map(t => `
        <div class="tag-dropdown-item ${t.tag.toLowerCase() === currentTagFilter.toLowerCase() ? 'active' : ''}" 
             onmousedown="selectTagFromCombobox('${escapeHtml(t.tag)}')">
            <span>#${escapeHtml(t.tag)}</span>
            <span class="tag-dropdown-count">${t.count}</span>
        </div>
    `).join("");
    menu.classList.remove("hidden");
}

function selectTagFromCombobox(tagName) {
    const input = document.getElementById("tagComboboxInput");
    const clearBtn = document.getElementById("tagClearBtn");
    const menu = document.getElementById("tagDropdownMenu");

    if (input) input.value = tagName;
    if (clearBtn) clearBtn.classList.remove("hidden");
    if (menu) menu.classList.add("hidden");

    currentTagFilter = tagName.toLowerCase();
    renderGrid();
}

function clearTagFilter() {
    const input = document.getElementById("tagComboboxInput");
    const clearBtn = document.getElementById("tagClearBtn");
    const menu = document.getElementById("tagDropdownMenu");

    if (input) input.value = "";
    if (clearBtn) clearBtn.classList.add("hidden");
    if (menu) menu.classList.add("hidden");

    currentTagFilter = "";
    renderGrid();
}

function filterByTag(tag) {
    selectTagFromCombobox(tag);
}

/* Autocomplete in Edit Modal for #editTags */
function handleEditTagsInput(val) {
    const menu = document.getElementById("editTagsAutocomplete");
    if (!menu) return;

    // Get current token being typed after last comma
    const parts = val.split(",");
    const currentToken = parts[parts.length - 1].trim().toLowerCase();

    if (!currentToken) {
        menu.classList.add("hidden");
        return;
    }

    const matches = allTags.filter(t => t.tag.toLowerCase().startsWith(currentToken));
    if (matches.length === 0) {
        menu.classList.add("hidden");
        return;
    }

    menu.innerHTML = matches.map(t => `
        <div class="tag-dropdown-item" onmousedown="insertEditTag('${escapeHtml(t.tag)}')">
            <span>#${escapeHtml(t.tag)}</span>
            <span class="tag-dropdown-count">${t.count}</span>
        </div>
    `).join("");
    menu.classList.remove("hidden");
}

function insertEditTag(tagName) {
    const input = document.getElementById("editTags");
    const menu = document.getElementById("editTagsAutocomplete");
    if (!input) return;

    const parts = input.value.split(",").map(p => p.trim()).filter(Boolean);
    if (parts.length > 0) parts.pop(); // Remove partial token
    parts.push(tagName);
    input.value = parts.join(", ") + ", ";
    if (menu) menu.classList.add("hidden");
    input.focus();
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

function handleSearch() {
    currentSearch = document.getElementById("searchInput").value.trim().toLowerCase();
    renderGrid();
}

function handleSortSelect(val) {
    currentSort = val;
    renderGrid();
}

/* Lucide SVG Icon Helper (Clean, Offline-Proof & Consistent) */
function lucide(name, className = "lucide-icon", size = 16) {
    const icons = {
        play: `<polygon points="6 3 20 12 6 21 6 3"></polygon>`,
        "play-fill": `<polygon points="6 3 20 12 6 21 6 3" fill="currentColor"></polygon>`,
        edit: `<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>`,
        pencil: `<line x1="18" y1="2" x2="22" y2="6"></line><path d="M7.5 20.5 19 9l-4-4L3.5 16.5 2 22z"></path>`,
        "rotate-ccw": `<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path><path d="M3 3v5h5"></path>`,
        "file-text": `<path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><line x1="10" y1="9" x2="8" y2="9"></line>`,
        tag: `<path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z"></path><path d="M7 7h.01"></path>`,
        clock: `<circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline>`,
        film: `<rect width="18" height="18" x="3" y="3" rx="2"></rect><path d="M7 3v18"></path><path d="M3 7.5h4"></path><path d="M3 12h18"></path><path d="M3 16.5h4"></path><path d="M17 3v18"></path><path d="M17 7.5h4"></path><path d="M17 16.5h4"></path>`,
        check: `<path d="M20 6 9 17l-5-5"></path>`,
        x: `<path d="M18 6 6 18"></path><path d="m6 6 12 12"></path>`
    };
    const body = icons[name] || icons["play"];
    return `<svg class="${className}" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
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
        const primaryTag = tagList.length > 0 ? tagList[0] : null;

        const notesText = (v.notes || "").trim();
        const hasNotes = notesText.length > 0;

        return `
            <div class="video-card" id="card-${v.id}">
                <div class="thumb-container" onclick="openPreview(${v.id}, '${escapeHtml(v.title)}')">
                    ${hasThumb 
                        ? `<img src="/api/v1/videos/${v.id}/thumbnail" alt="${escapeHtml(v.title)}" class="thumb-img" loading="lazy">`
                        : `<div class="thumb-placeholder">${lucide('film', 'thumb-placeholder-icon', 32)}</div>`
                    }
                    <div class="thumb-gradient"></div>

                    <!-- Center Hover Play Overlay -->
                    <div class="play-hover-overlay">
                        <div class="play-hover-btn" title="Play Video">
                            ${lucide('play-fill', 'icon-play-fill', 20)}
                        </div>
                    </div>

                    <!-- Quick Hover Actions (Top Right) -->
                    <div class="thumb-quick-actions" onclick="event.stopPropagation()">
                        <button class="quick-action-btn" onclick="openEditModal(${v.id})" title="Rename & Edit Details">
                            ${lucide('pencil', 'icon-quick', 13)}
                        </button>
                        <button class="quick-action-btn" onclick="resetVideoProgress(${v.id})" title="Reset Watch Progress">
                            ${lucide('rotate-ccw', 'icon-quick', 13)}
                        </button>
                    </div>

                    <!-- Corner Badges -->
                    <span class="res-badge">${resBadge}</span>
                    <span class="duration-badge">
                        ${lucide('clock', 'icon-clock-badge', 10)}
                        <span>${formatDuration(v.duration)}</span>
                    </span>
                    ${isRated ? `<span class="thumb-rating-badge">${circleSymbol}</span>` : ''}

                    <!-- Integrated Bottom Progress Bar -->
                    ${progressPct > 0 ? `
                        <div class="card-progress-bar">
                            <div class="card-progress-fill" style="width: ${progressPct}%;"></div>
                        </div>
                    ` : ''}
                </div>

                <div class="card-body">
                    <h4 class="video-title" onclick="openPreview(${v.id}, '${escapeHtml(v.title)}')" title="File: ${escapeHtml(v.filename)}">${escapeHtml(v.title)}</h4>

                    <div class="card-meta-footer">
                        <div class="meta-footer-left">
                            <span class="time-label">${progressPct > 0 ? `${progressPct}% watched` : formatDuration(v.duration)}</span>
                            ${primaryTag ? `<span class="pill-tag-sm" onclick="filterByTag('${escapeHtml(primaryTag)}')" title="Filter by #${escapeHtml(primaryTag)}">${lucide('tag', 'icon-tag-pill', 10)} #${escapeHtml(primaryTag)}</span>` : ''}
                            ${tagList.length > 1 ? `<span class="pill-tag-sm" onclick="openEditModal(${v.id})" title="All tags: ${escapeHtml(tagList.join(', '))}">+${tagList.length - 1}</span>` : ''}
                        </div>

                        <div class="meta-footer-right">
                            ${hasNotes ? `
                                <button class="pill-notes-btn active" onclick="openEditModal(${v.id})" title="Notes: ${escapeHtml(notesText)}">
                                    ${lucide('file-text', 'icon-notes', 13)}
                                </button>
                            ` : ''}
                            <button class="circle-symbol-btn ${isRated ? 'rated' : 'unrated'}" 
                                    onclick="cycleRating(${v.id}, ${v.rating})" 
                                    title="Click to cycle rating (○ -> ● -> ●●)">
                                ${circleSymbol}
                            </button>
                            <button class="card-action-icon-btn" onclick="openEditModal(${v.id})" title="Rename video / edit details">
                                ${lucide('pencil', 'icon-pencil-sm', 12)}
                            </button>
                        </div>
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
    document.getElementById("editModalTitle").textContent = `Edit Video Details`;
    document.getElementById("editVideoTitle").value = video.title || "";
    
    // Extract file extension to display in badge
    let ext = "";
    if (video.filename && video.filename.lastIndexOf(".") !== -1) {
        ext = video.filename.substring(video.filename.lastIndexOf("."));
    } else if (video.filepath && video.filepath.lastIndexOf(".") !== -1) {
        ext = video.filepath.substring(video.filepath.lastIndexOf("."));
    }
    document.getElementById("editVideoExt").textContent = ext || "";

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

    const title = document.getElementById("editVideoTitle").value.trim();
    if (!title) {
        showAlert("Video title cannot be empty", "error");
        return;
    }

    const notes = document.getElementById("editNotes").value;
    const tags = document.getElementById("editTags").value;
    const rating = modalSelectedRating;

    try {
        const res = await fetch(`/api/v1/videos/${videoId}/metadata`, {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                title: title,
                notes: notes,
                tags: tags,
                rating: rating
            })
        });

        if (res.ok) {
            const data = await res.json();
            const updatedVideo = data.video;
            const v = allVideos.find(item => item.id === videoId);
            if (v) {
                if (updatedVideo) {
                    Object.assign(v, updatedVideo);
                } else {
                    v.title = title;
                    v.notes = notes;
                    v.tags = tags;
                    v.rating = rating;
                }
            }
            updateCounts();
            renderGrid();
            await loadTags();
            document.getElementById("editModal").classList.add("hidden");
            showAlert("Video details saved & file renamed on hard drive", "success");
        } else {
            const errData = await res.json().catch(() => ({}));
            showAlert(errData.detail || "Failed to save video details", "error");
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
    
    let progFill = card.querySelector(".card-progress-fill");
    if (!progFill && progressPct > 0) {
        const thumbContainer = card.querySelector(".thumb-container");
        if (thumbContainer) {
            const bar = document.createElement("div");
            bar.className = "card-progress-bar";
            bar.innerHTML = `<div class="card-progress-fill" style="width: ${progressPct}%;"></div>`;
            thumbContainer.appendChild(bar);
        }
    } else if (progFill) {
        progFill.style.width = `${progressPct}%`;
    }

    const timeLabel = card.querySelector(".time-label");
    if (timeLabel) {
        timeLabel.textContent = progressPct > 0 ? `${progressPct}% watched` : formatDuration(v.duration);
    }
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
