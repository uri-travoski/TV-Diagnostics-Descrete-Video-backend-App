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
import com.tvdiagnostics.app.security.PanicManager
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

    private var allVideos = listOf<VideoItem>()
    private var allTags = listOf<TagItem>()

    // Filter and Sort states
    private var selectedRatingFilter: Int? = null
    private var isContinueFilter: Boolean = false
    private var selectedTag: String? = null
    private var searchQuery: String = ""
    private var currentSort: SortOption = SortOption.RECENT_DESC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent recent apps task switcher from capturing preview thumbnails
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_vault)

        initViews()
        setupFilters()
        setupSearch()
        loadTags()
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        // Refresh video list & progress when returning from player or settings
        loadVideos()
    }

    private fun initViews() {
        rvVideos = findViewById(R.id.rvVideoGrid)
        // 4 columns on standard 1080p/4K TV screens
        rvVideos.layoutManager = GridLayoutManager(this, 4)

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
            val sortDialog = SortDialog(this, currentSort) { selectedSort ->
                currentSort = selectedSort
                btnVaultSort.text = "⇅ ${selectedSort.displayName}"
                applyFilters()
            }
            sortDialog.show()
        }

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
                rvVideos.requestFocus()
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

        val ratingButtons = listOf(btnAll, btnContinue, btnL2, btnL1, btnUnrated)

        fun highlightButton(active: Button) {
            ratingButtons.forEach { b ->
                if (b == active) {
                    b.setBackgroundResource(R.drawable.btn_tv_focus)
                }
            }
        }

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
            btnVaultTags.text = "🏷 #${selectedTag}$countStr"
            btnVaultTags.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            btnClearTagFilter.visibility = View.VISIBLE
        } else {
            btnVaultTags.text = "🏷 Tags: All"
            btnVaultTags.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            btnClearTagFilter.visibility = View.GONE
        }
    }

    private fun loadVideos() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                allVideos = ApiClient.fetchVideos()
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

        val sortedList = filtered.sortedWith { a, b ->
            when (currentSort) {
                SortOption.TITLE_ASC -> (a.title.ifBlank { a.filename }).compareTo(b.title.ifBlank { b.filename }, ignoreCase = true)
                SortOption.TITLE_DESC -> (b.title.ifBlank { b.filename }).compareTo(a.title.ifBlank { a.filename }, ignoreCase = true)
                SortOption.RATING_DESC -> b.rating.compareTo(a.rating)
                SortOption.RATING_ASC -> a.rating.compareTo(b.rating)
                SortOption.RECENT_DESC -> b.id.compareTo(a.id)
                SortOption.RECENT_ASC -> a.id.compareTo(b.id)
                SortOption.DURATION_DESC -> b.duration.compareTo(a.duration)
                SortOption.DURATION_ASC -> a.duration.compareTo(b.duration)
                SortOption.PROGRESS_DESC -> {
                    val pctA = if (a.duration > 0) a.watchedSeconds / a.duration else 0.0
                    val pctB = if (b.duration > 0) b.watchedSeconds / b.duration else 0.0
                    pctB.compareTo(pctA)
                }
            }
        }

        adapter.updateData(sortedList)
    }

    private fun lockAndExit() {
        TVDiagnosticsApp.instance.preferences.lockVault()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Intercept Panic events (Double BACK or MENU key)
        if (PanicManager.handleKeyEvent(this, keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
