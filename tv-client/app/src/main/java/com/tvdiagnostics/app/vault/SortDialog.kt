package com.tvdiagnostics.app.vault

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import androidx.core.content.ContextCompat
import com.tvdiagnostics.app.R

enum class SortOption(val displayName: String) {
    TITLE_ASC("Title (A-Z)"),
    TITLE_DESC("Title (Z-A)"),
    RATING_DESC("Rating (Highest)"),
    RATING_ASC("Rating (Lowest)"),
    RECENT_DESC("Added (Newest)"),
    RECENT_ASC("Added (Oldest)"),
    DURATION_DESC("Duration (Longest)"),
    DURATION_ASC("Duration (Shortest)"),
    PROGRESS_DESC("Progress (%)")
}

class SortDialog(
    context: Context,
    private val currentSort: SortOption,
    private val onSortSelected: (SortOption) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_sort)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val buttonMap = mapOf(
            SortOption.TITLE_ASC to findViewById<Button>(R.id.btnSortTitleAsc),
            SortOption.TITLE_DESC to findViewById<Button>(R.id.btnSortTitleDesc),
            SortOption.RATING_DESC to findViewById<Button>(R.id.btnSortRatingDesc),
            SortOption.RATING_ASC to findViewById<Button>(R.id.btnSortRatingAsc),
            SortOption.RECENT_DESC to findViewById<Button>(R.id.btnSortRecentDesc),
            SortOption.RECENT_ASC to findViewById<Button>(R.id.btnSortRecentAsc),
            SortOption.DURATION_DESC to findViewById<Button>(R.id.btnSortDurationDesc),
            SortOption.DURATION_ASC to findViewById<Button>(R.id.btnSortDurationAsc),
            SortOption.PROGRESS_DESC to findViewById<Button>(R.id.btnSortProgressDesc)
        )

        buttonMap.forEach { (option, btn) ->
            if (option == currentSort) {
                btn.setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                btn.requestFocus()
            }
            btn.setOnClickListener {
                onSortSelected(option)
                dismiss()
            }
        }

        findViewById<Button>(R.id.btnSortClose).setOnClickListener {
            dismiss()
        }
    }
}
