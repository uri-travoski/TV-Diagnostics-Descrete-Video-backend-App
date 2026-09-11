package com.tvdiagnostics.app.vault

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.data.TagItem

class TagFilterDialog(
    context: Context,
    private val allTags: List<TagItem>,
    private val currentSelectedTag: String?,
    private val onTagSelected: (String?) -> Unit
) : Dialog(context) {

    private lateinit var etTagFilterSearch: EditText
    private lateinit var gridTagsContainer: GridLayout
    private lateinit var tvNoTagsFound: TextView
    private lateinit var layoutAlphabetPills: LinearLayout

    private var currentFilterQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_tags)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Prevent virtual keyboard from popping up automatically on TV
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        etTagFilterSearch = findViewById(R.id.etTagFilterSearch)
        gridTagsContainer = findViewById(R.id.gridTagsContainer)
        tvNoTagsFound = findViewById(R.id.tvNoTagsFound)
        layoutAlphabetPills = findViewById(R.id.layoutAlphabetPills)

        findViewById<Button>(R.id.btnTagClose).setOnClickListener {
            dismiss()
        }

        findViewById<Button>(R.id.btnTagResetAll).setOnClickListener {
            onTagSelected(null)
            dismiss()
        }

        setupAlphabetBar()
        setupSearchInput()
        populateTags(allTags)
    }

    private fun setupSearchInput() {
        etTagFilterSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentFilterQuery = s?.toString()?.trim() ?: ""
                filterAndDisplayTags()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupAlphabetBar() {
        layoutAlphabetPills.removeAllViews()
        val letters = listOf("ALL") + ('A'..'Z').map { it.toString() }

        for (letter in letters) {
            val btn = Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 6
                }
                minWidth = 44
                height = 38
                text = letter
                textSize = 13f
                setBackgroundResource(R.drawable.btn_tv_focus)
                setTextColor(ContextCompat.getColor(context, R.color.text_white))
                isFocusable = true
                isClickable = true
                nextFocusDownId = R.id.gridTagsContainer
                nextFocusUpId = R.id.etTagFilterSearch

                setOnFocusChangeListener { v, hasFocus ->
                    v.animate()
                        .scaleX(if (hasFocus) 1.15f else 1.0f)
                        .scaleY(if (hasFocus) 1.15f else 1.0f)
                        .setDuration(120)
                        .start()
                }

                setOnClickListener {
                    if (letter == "ALL") {
                        etTagFilterSearch.setText("")
                    } else {
                        etTagFilterSearch.setText(letter)
                        etTagFilterSearch.setSelection(letter.length)
                    }
                }
            }
            layoutAlphabetPills.addView(btn)
        }
    }

    private fun filterAndDisplayTags() {
        val filtered = if (currentFilterQuery.isEmpty()) {
            allTags
        } else {
            allTags.filter { it.tag.startsWith(currentFilterQuery, ignoreCase = true) }
        }
        populateTags(filtered)
    }

    private fun populateTags(tags: List<TagItem>) {
        gridTagsContainer.removeAllViews()

        if (tags.isEmpty()) {
            tvNoTagsFound.visibility = View.VISIBLE
            tvNoTagsFound.text = if (currentFilterQuery.isNotEmpty()) {
                "No tags starting with \"$currentFilterQuery\""
            } else {
                "No tags available."
            }
            if (layoutAlphabetPills.childCount > 0) {
                layoutAlphabetPills.getChildAt(0).requestFocus()
            }
            return
        }

        tvNoTagsFound.visibility = View.GONE

        var focusedView: View? = null

        for (tagItem in tags) {
            val cleanTag = tagItem.tag.trim()
            if (cleanTag.isEmpty()) continue

            val btn = Button(context).apply {
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 48
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(6, 6, 6, 6)
                }
                layoutParams = params
                text = "#$cleanTag (${tagItem.count})"
                textSize = 14f
                setPadding(12, 0, 12, 0)
                setBackgroundResource(R.drawable.btn_tv_focus)
                isFocusable = true
                isClickable = true
                nextFocusUpId = R.id.hsvAlphabet

                setOnFocusChangeListener { v, hasFocus ->
                    v.animate()
                        .scaleX(if (hasFocus) 1.06f else 1.0f)
                        .scaleY(if (hasFocus) 1.06f else 1.0f)
                        .setDuration(120)
                        .start()
                }

                if (cleanTag.equals(currentSelectedTag, ignoreCase = true)) {
                    setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                    focusedView = this
                } else {
                    setTextColor(ContextCompat.getColor(context, R.color.text_white))
                }

                setOnClickListener {
                    onTagSelected(cleanTag)
                    dismiss()
                }
            }
            gridTagsContainer.addView(btn)
        }

        if (focusedView != null) {
            focusedView?.requestFocus()
        } else if (layoutAlphabetPills.childCount > 0) {
            layoutAlphabetPills.getChildAt(0).requestFocus()
        }
    }
}
