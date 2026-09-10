package com.tvdiagnostics.app.vault

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
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
    private lateinit var layoutTagChips: LinearLayout
    private lateinit var btnTagAll: Button

    private var allVideos = listOf<VideoItem>()
    private var allTags = listOf<TagItem>()

    // Filter states
    private var selectedRatingFilter: Int? = null
    private var isContinueFilter: Boolean = false
    private var selectedTag: String? = null
    private var searchQuery: String = ""

    private val tagButtons = mutableListOf<Button>()

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
        layoutTagChips = findViewById(R.id.layoutTagChips)
        btnTagAll = findViewById(R.id.btnTagAll)

        btnTagAll.setOnClickListener {
            selectedTag = null
            highlightActiveTagButton(btnTagAll)
            applyFilters()
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
                populateTagChips()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun populateTagChips() {
        // Keep btnTagAll, remove existing dynamic tag buttons if any
        if (layoutTagChips.childCount > 1) {
            layoutTagChips.removeViews(1, layoutTagChips.childCount - 1)
        }
        tagButtons.clear()
        tagButtons.add(btnTagAll)

        for (tagItem in allTags) {
            val tagName = tagItem.tag.trim()
            if (tagName.isEmpty()) continue

            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(R.dimen.tag_button_height)
                ).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.tag_button_margin_end)
                }
                text = "$tagName (${tagItem.count})"
                setBackgroundResource(R.drawable.btn_tv_focus)
                setTextColor(ContextCompat.getColor(context, R.color.text_white))
                textSize = 11f
                isFocusable = true
                isClickable = true

                setOnClickListener {
                    selectedTag = tagName
                    highlightActiveTagButton(this)
                    applyFilters()
                }
            }

            tagButtons.add(btn)
            layoutTagChips.addView(btn)
        }

        val activeBtn = selectedTag?.let { tag -> tagButtons.find { it.text.startsWith(tag) } } ?: btnTagAll
        highlightActiveTagButton(activeBtn)
    }

    private fun highlightActiveTagButton(active: Button) {
        for (btn in tagButtons) {
            if (btn == active) {
                btn.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
            } else {
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            }
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

        adapter.updateData(filtered)
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
