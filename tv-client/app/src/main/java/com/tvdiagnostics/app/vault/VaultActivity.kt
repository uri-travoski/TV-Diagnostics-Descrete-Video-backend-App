package com.tvdiagnostics.app.vault

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.TagItem
import com.tvdiagnostics.app.data.VideoItem
import com.tvdiagnostics.app.decoy.SettingsDialog
import com.tvdiagnostics.app.security.LockReason
import com.tvdiagnostics.app.security.PanicManager
import com.tvdiagnostics.app.security.VaultLock
import com.tvdiagnostics.app.ui.UiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VaultActivity : AppCompatActivity() {

    private lateinit var rvVideos: RecyclerView
    private lateinit var adapter: VideoAdapter
    private lateinit var etSearch: EditText
    private lateinit var btnVaultTags: Button
    private lateinit var btnClearTagFilter: Button
    private lateinit var btnVaultSort: Button
    private lateinit var btnVaultFolders: Button

    private var allVideos = listOf<VideoItem>()
    private var allTags = listOf<TagItem>()

    // Filter and Sort states
    private var selectedRatingFilter: Int? = null
    private var isContinueFilter: Boolean = false
    private var selectedTag: String? = null
    private var searchQuery: String = ""
    private var selectedFolder: String? = null
    private var currentSort: SortOption = SortOption.fromName(TVDiagnosticsApp.instance.preferences.librarySort)

    private var didInitialFocus = false
    private lateinit var tvVaultCount: TextView
    private lateinit var tvEmptyState: TextView
    private lateinit var ratingButtons: List<Button>

    /** TV or mobile layout this screen was built with; rebuilt when Settings switches it. */
    private var builtMode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VaultLock.redirectIfLocked(this)) return
        VaultLock.registerVaultActivity(this)

        // Prevent recent apps task switcher from capturing preview thumbnails
        VaultLock.secureWindow(this)

        builtMode = UiMode.current
        UiMode.applyOrientation(this)
        setContentView(UiMode.layout(R.layout.activity_vault, R.layout.activity_vault_mobile))

        if (UiMode.isMobile) {
            // Phone: keyboard only when the search field is tapped, and the grid shrinks above it
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        } else {
            // Prevent virtual keyboard from popping up automatically on TV
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }

        // Leaving the library for the start screen locks the vault (the code is needed to come back)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                VaultLock.lock(LockReason.BACK)
            }
        })

        initViews()
        setupFilters()
        setupSearch()
        loadTags()
        // Videos load in onResume (first open and every return from the player)
    }

    override fun onResume() {
        super.onResume()
        if (VaultLock.redirectIfLocked(this)) return
        if (builtMode != UiMode.current) {
            recreate()
            return
        }
        // Refresh video list & progress when returning from player or settings
        loadVideos()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        VaultLock.touch()
    }

    private fun initViews() {
        rvVideos = findViewById(R.id.rvVideoGrid)
        // 5 columns of 16:9 tiles on 1080p/4K TV screens; on a phone as many as fit (2 upright)
        rvVideos.layoutManager = GridLayoutManager(this, gridColumns())
        rvVideos.itemAnimator = null

        tvVaultCount = findViewById(R.id.tvVaultCount)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        adapter = VideoAdapter(emptyList()) { selectedVideo ->
            val dialog = VideoDetailDialog(this, selectedVideo) {
                adapter.notifyDataSetChanged()
            }
            dialog.show()
        }
        rvVideos.adapter = adapter

        etSearch = findViewById(R.id.etVaultSearch)
        btnVaultTags = findViewById(R.id.btnVaultTags)
        btnClearTagFilter = findViewById(R.id.btnClearTagFilter)

        btnVaultTags.setOnClickListener {
            val dialog = TagFilterDialog(this, allTags, selectedTag) { tag ->
                selectedTag = tag
                updateTagButtonUi()
                applyFilters()
            }
            dialog.show()
        }

        btnClearTagFilter.setOnClickListener {
            selectedTag = null
            updateTagButtonUi()
            applyFilters()
        }

        btnVaultSort = findViewById(R.id.btnVaultSort)
        btnVaultSort.text = "⇅ ${currentSort.displayName}"
        btnVaultSort.setOnClickListener {
            val choices = SortOption.values().map { Choice(it, it.displayName) }
            ChoiceDialog(this, "⇅ Sort by", choices, currentSort) { selectedSort ->
                currentSort = selectedSort
                TVDiagnosticsApp.instance.preferences.librarySort = selectedSort.name
                btnVaultSort.text = "⇅ ${selectedSort.displayName}"
                applyFilters()
            }.show()
        }

        btnVaultFolders = findViewById(R.id.btnVaultFolders)
        btnVaultFolders.setOnClickListener { showFolderPicker() }

        findViewById<Button>(R.id.btnVaultSettings).setOnClickListener {
            val settingsDialog = SettingsDialog(this) {
                // Reload when settings dismisses (e.g. notes toggled or URL changed)
                loadTags()
                loadVideos()
            }
            settingsDialog.show()
        }

        findViewById<Button>(R.id.btnLockVault).setOnClickListener {
            lockAndExit()
        }

        // Set default D-pad remote focus on the first filter pill
        findViewById<Button>(R.id.filterAll)?.requestFocus()
    }

    private fun gridColumns(): Int {
        if (!UiMode.isMobile) return 5
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / MOBILE_TILE_DP).coerceIn(2, 6)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Phone turned: the screen isn't rebuilt (filters and scroll position stay), only the columns change
        (rvVideos.layoutManager as? GridLayoutManager)?.spanCount = gridColumns()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                searchQuery = etSearch.text.toString().trim().lowercase()
                applyFilters()
                if (UiMode.isMobile) hideKeyboard() else rvVideos.requestFocus()
                true
            } else {
                false
            }
        }
    }

    private fun setupFilters() {
        val btnAll = findViewById<Button>(R.id.filterAll)
        val btnContinue = findViewById<Button>(R.id.filterContinue)
        val btnL2 = findViewById<Button>(R.id.filterLevel2)
        val btnL1 = findViewById<Button>(R.id.filterLevel1)
        val btnUnrated = findViewById<Button>(R.id.filterUnrated)

        ratingButtons = listOf(btnAll, btnContinue, btnL2, btnL1, btnUnrated)

        fun highlightButton(active: Button) {
            ratingButtons.forEach { b -> b.isSelected = b == active }
        }
        highlightButton(btnAll)

        btnAll.setOnClickListener {
            selectedRatingFilter = null
            isContinueFilter = false
            highlightButton(btnAll)
            applyFilters()
        }

        btnContinue.setOnClickListener {
            selectedRatingFilter = null
            isContinueFilter = true
            highlightButton(btnContinue)
            applyFilters()
        }

        btnL2.setOnClickListener {
            selectedRatingFilter = 2
            isContinueFilter = false
            highlightButton(btnL2)
            applyFilters()
        }

        btnL1.setOnClickListener {
            selectedRatingFilter = 1
            isContinueFilter = false
            highlightButton(btnL1)
            applyFilters()
        }

        btnUnrated.setOnClickListener {
            selectedRatingFilter = 0
            isContinueFilter = false
            highlightButton(btnUnrated)
            applyFilters()
        }
    }

    /** Every folder holding videos (and its parents), indented under its parent, with video counts. */
    private fun showFolderPicker() {
        val paths = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        for (v in allVideos) {
            val parts = v.folder.split('/').filter { it.isNotEmpty() }
            for (i in 1..parts.size) paths.add(parts.take(i).joinToString("/"))
        }
        fun count(path: String) = allVideos.count { it.folder == path || it.folder.startsWith("$path/") }
        val choices = listOf(Choice<String?>(null, "All folders", trailing = "${allVideos.size}")) +
            paths.map { path ->
                Choice<String?>(path, path.substringAfterLast('/'), depth = path.count { it == '/' } + 1, trailing = "${count(path)}")
            }
        ChoiceDialog(this, "📁 Folders", choices, selectedFolder) { folder ->
            selectedFolder = folder
            updateFolderButtonUi()
            applyFilters()
        }.show()
    }

    private fun updateFolderButtonUi() {
        val folder = selectedFolder
        btnVaultFolders.text = if (folder == null) "📁 All folders" else "📁 ${folder.substringAfterLast('/')}"
        btnVaultFolders.isSelected = folder != null
        // One folder without subfolders: nothing to choose between
        val distinct = allVideos.map { it.folder }.toSet()
        btnVaultFolders.visibility = if (folder != null || distinct.size > 1) View.VISIBLE else View.GONE
    }

    private fun loadTags() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                allTags = ApiClient.fetchTags()
                updateTagButtonUi()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateTagButtonUi() {
        if (selectedTag != null) {
            val match = allTags.find { it.tag.equals(selectedTag, ignoreCase = true) }
            val countStr = match?.let { " (${it.count})" } ?: ""
            btnVaultTags.text = "#${selectedTag}$countStr"
            btnVaultTags.isSelected = true
            btnClearTagFilter.visibility = View.VISIBLE
            btnVaultTags.nextFocusRightId = R.id.btnClearTagFilter
            btnVaultSort.nextFocusLeftId = R.id.btnClearTagFilter
        } else {
            btnVaultTags.text = "# Tags"
            btnVaultTags.isSelected = false
            btnClearTagFilter.visibility = View.GONE
            btnVaultTags.nextFocusRightId = R.id.btnVaultSort
            btnVaultSort.nextFocusLeftId = R.id.btnVaultTags
        }
    }

    private fun loadVideos() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val videos = ApiClient.fetchVideos()
                if (videos == null) {
                    allVideos = emptyList()
                    applyFilters()
                    showEmptyState("Diagnostic server unreachable. Check ⚙ connection settings.")
                    return@launch
                }
                // Start on the first video so OK opens it right away and the D-pad browses the grid
                if (!didInitialFocus && videos.isNotEmpty()) {
                    didInitialFocus = true
                    adapter.focusFirstOnBind = true
                }
                allVideos = videos
                // A folder emptied or removed since it was picked: back to all folders
                if (selectedFolder?.let { f -> videos.none { it.folder == f || it.folder.startsWith("$f/") } } == true) {
                    selectedFolder = null
                }
                updateFolderButtonUi()
                applyFilters()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun applyFilters() {
        val isNotesUnlocked = TVDiagnosticsApp.instance.preferences.isNotesEnabled

        val filtered = allVideos.filter { v ->
            // Rating / Continue filter
            if (isContinueFilter) {
                if (v.watchedSeconds <= 0 || v.completed != 0) return@filter false
            } else if (selectedRatingFilter != null && v.rating != selectedRatingFilter) {
                return@filter false
            }

            // Folder filter (includes its subfolders)
            selectedFolder?.let { f ->
                if (v.folder != f && !v.folder.startsWith("$f/")) return@filter false
            }

            // Tag filter
            if (selectedTag != null) {
                val tags = v.getTagList()
                if (!tags.any { it.equals(selectedTag, ignoreCase = true) }) {
                    return@filter false
                }
            }

            // Search query filter
            if (searchQuery.isNotEmpty()) {
                val matchTitle = v.title.lowercase().contains(searchQuery)
                val matchFile = v.filename.lowercase().contains(searchQuery)
                val matchTags = v.tags.lowercase().contains(searchQuery)
                val matchNotes = isNotesUnlocked && v.notes.lowercase().contains(searchQuery)
                if (!matchTitle && !matchFile && !matchTags && !matchNotes) {
                    return@filter false
                }
            }

            true
        }

        val sortedList = filtered.sortedWith(currentSort.comparator)

        adapter.updateData(sortedList)

        val noun = if (allVideos.size == 1) "video" else "videos"
        tvVaultCount.text = if (sortedList.size == allVideos.size) {
            "${allVideos.size} $noun"
        } else {
            "${sortedList.size} of ${allVideos.size} $noun"
        }
        when {
            allVideos.isEmpty() -> showEmptyState("No videos in this library yet.")
            sortedList.isEmpty() -> showEmptyState("Nothing matches these filters.")
            else -> tvEmptyState.visibility = View.GONE
        }
    }

    private fun showEmptyState(message: String) {
        tvEmptyState.text = message
        tvEmptyState.visibility = View.VISIBLE
    }

    private fun lockAndExit() {
        VaultLock.lock(LockReason.MANUAL)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Intercept Panic events (Double BACK or MENU key)
        if (PanicManager.handleKeyEvent(this, keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        /** Phone grid: one column per this much screen width */
        private const val MOBILE_TILE_DP = 180
    }
}
