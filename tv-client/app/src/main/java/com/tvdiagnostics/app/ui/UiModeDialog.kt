package com.tvdiagnostics.app.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.security.VaultLock

/**
 * "TV or phone?" Asked once on first launch (can't be skipped) and again from Settings.
 * [onChosen] gets UiMode.TV or UiMode.MOBILE.
 */
class UiModeDialog(
    context: Context,
    private val firstLaunch: Boolean,
    private val onChosen: (String) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_ui_mode)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        VaultLock.trackInput(window)
        setCancelable(!firstLaunch)

        // Fits a TV and a phone held upright alike
        val metrics = context.resources.displayMetrics
        val width = minOf(UiMode.dp(context, 460), metrics.widthPixels - UiMode.dp(context, 32))
        window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        if (!firstLaunch) findViewById<TextView>(R.id.tvUiModeTitle).text = "Screen layout"

        val current = UiMode.current
        val btnTv = findViewById<Button>(R.id.btnModeTv)
        val btnMobile = findViewById<Button>(R.id.btnModeMobile)
        btnTv.text = label("📺   TV or TV box", "Use it with a remote control", current == UiMode.TV)
        btnMobile.text = label("📱   Phone or tablet", "Use it with the touch screen", current == UiMode.MOBILE)

        btnTv.setOnClickListener { choose(UiMode.TV) }
        btnMobile.setOnClickListener { choose(UiMode.MOBILE) }

        // Remote users land on the likely answer; OK confirms it
        val suggested = when (current) {
            UiMode.TV -> btnTv
            UiMode.MOBILE -> btnMobile
            else -> if (UiMode.looksLikeTv(context)) btnTv else btnMobile
        }
        suggested.post { suggested.requestFocus() }
    }

    private fun choose(mode: String) {
        dismiss()
        onChosen(mode)
    }

    private fun label(title: String, subtitle: String, isCurrent: Boolean): CharSequence {
        val text = SpannableStringBuilder(title)
        if (isCurrent) text.append("  ✓")
        val start = text.length
        text.append("\n").append(subtitle)
        text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.text_muted)),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }
}
