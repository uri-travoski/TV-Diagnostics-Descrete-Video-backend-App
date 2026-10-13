package com.tvdiagnostics.app.vault

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.security.VaultLock

/** One row of a [ChoiceDialog]; [depth] indents it (subfolders). */
data class Choice<T>(val value: T, val label: String, val depth: Int = 0, val trailing: String = "")

/** Remote-friendly single-choice list: UP/DOWN to move, OK to pick. Opens on the current choice. */
class ChoiceDialog<T>(
    context: Context,
    private val title: String,
    private val choices: List<Choice<T>>,
    private val selected: T,
    private val onChosen: (T) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_choices)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        VaultLock.trackInput(window)

        findViewById<TextView>(R.id.tvChoiceTitle).text = title
        val container = findViewById<LinearLayout>(R.id.choiceContainer)
        val density = context.resources.displayMetrics.density
        var current: Button? = null

        for (choice in choices) {
            val isSelected = choice.value == selected
            val btn = Button(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * density).toInt()).apply {
                    bottomMargin = (4 * density).toInt()
                }
                isAllCaps = false
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(((14 + 18 * choice.depth) * density).toInt(), 0, (14 * density).toInt(), 0)
                text = if (choice.trailing.isEmpty()) choice.label else "${choice.label}   ·  ${choice.trailing}"
                setBackgroundResource(R.drawable.btn_tv_focus)
                setTextColor(ContextCompat.getColor(context, if (isSelected) R.color.accent_blue else R.color.text_white))
                if (isSelected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    onChosen(choice.value)
                    dismiss()
                }
            }
            if (isSelected) current = btn
            container.addView(btn)
        }

        // Long folder lists: cap the height so the dialog stays on screen
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.7).toInt()
        val scroll = findViewById<ScrollView>(R.id.svChoices)
        scroll.post {
            if (scroll.height > maxHeight) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
            }
        }
        (current ?: container.getChildAt(0))?.let { focus -> focus.post { focus.requestFocus() } }
    }
}
