package com.tvdiagnostics.app.ui

import android.app.Activity
import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.ViewGroup
import com.tvdiagnostics.app.TVDiagnosticsApp

/**
 * TV (remote control, landscape) or mobile (touch, any orientation) interface. Chosen on first
 * launch and changeable in Settings; every screen picks its layout and controls from it.
 */
object UiMode {
    const val TV = "tv"
    const val MOBILE = "mobile"

    /** Null until the user has answered the first-launch question. */
    val current: String? get() = TVDiagnosticsApp.instance.preferences.uiMode

    val isMobile: Boolean get() = current == MOBILE

    fun set(mode: String) {
        TVDiagnosticsApp.instance.preferences.uiMode = mode
    }

    /** Best guess for the first-launch question: TVs and boxes without a touch screen get TV. */
    fun looksLikeTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val pm = context.packageManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun layout(tv: Int, mobile: Int): Int = if (isMobile) mobile else tv

    /**
     * Code keypads: the tall phone keypad only fits a phone held upright. A sideways phone
     * gets the side-by-side TV keypad (the dialog is rebuilt when the phone turns).
     */
    fun keypadLayout(context: Context, tv: Int, mobile: Int): Int =
        if (isMobile && context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) mobile else tv

    /**
     * TV: always landscape. Mobile: screens follow the phone (respecting its rotation lock,
     * so they return upright after the player), the player turns sideways.
     */
    fun applyOrientation(activity: Activity, player: Boolean = false) {
        activity.requestedOrientation = when {
            !isMobile || player -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    /**
     * TV dialogs are laid out at a fixed width; on a phone that is wider than the screen.
     * Mobile: the dialog fills the width (up to [maxWidthDp]) with a margin each side.
     */
    fun fitDialog(dialog: Dialog, maxWidthDp: Int) {
        if (!isMobile) return
        val window = dialog.window ?: return
        val metrics = dialog.context.resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        val width = minOf((maxWidthDp * metrics.density).toInt(), metrics.widthPixels - 2 * margin)
        dialog.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)?.let { root ->
            root.layoutParams = root.layoutParams?.apply { this.width = ViewGroup.LayoutParams.MATCH_PARENT }
        }
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

/** The activity behind a context (dialogs hand out a themed wrapper around it). */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
