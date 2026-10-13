package com.tvdiagnostics.app.decoy

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.data.ServerStatus
import com.tvdiagnostics.app.security.VaultLock
import com.tvdiagnostics.app.ui.UiMode
import com.tvdiagnostics.app.ui.UiModeDialog
import com.tvdiagnostics.app.ui.findActivity
import com.tvdiagnostics.app.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SettingsDialog(
    context: Context,
    private val onDismissCallback: (() -> Unit)? = null
) : Dialog(context) {

    private lateinit var etServerUrl: EditText
    private lateinit var tvDeviceIpHint: TextView
    private lateinit var etNewPin: EditText
    private lateinit var etConfirmPin: EditText
    private lateinit var btnChangePin: Button
    private lateinit var tvChangePinStatus: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var btnCheckUpdate: Button
    private var availableUpdate: AppUpdater.Release? = null
    private var isUpdating = false
    private lateinit var tvNotesStatus: TextView
    private lateinit var btnToggleNotes: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnTestConnection: Button
    private lateinit var tvAppVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings)

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Prevent virtual keyboard from popping up automatically on TV
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        VaultLock.trackInput(window)
        UiMode.fitDialog(this, 560)
        if (UiMode.isMobile) {
            // Phone: the settings body gets most of the screen height (any orientation)
            val body = findViewById<View>(R.id.svSettingsBody)
            body.layoutParams = body.layoutParams.apply {
                height = (context.resources.displayMetrics.heightPixels * 0.55).toInt()
            }
            findViewById<EditText>(R.id.etConfirmPin).hint = "Repeat"
            // Cancel / Save share the width instead of crowding the right edge
            listOf(R.id.btnSettingsCancel, R.id.btnSettingsSave).forEach { id ->
                val btn = findViewById<Button>(id)
                btn.layoutParams = (btn.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    width = 0
                    weight = 1f
                    height = UiMode.dp(context, 48)
                }
            }
        }

        etServerUrl = findViewById(R.id.etServerUrl)
        tvDeviceIpHint = findViewById(R.id.tvDeviceIpHint)
        etNewPin = findViewById(R.id.etNewPin)
        etConfirmPin = findViewById(R.id.etConfirmPin)
        btnChangePin = findViewById(R.id.btnChangePin)
        tvChangePinStatus = findViewById(R.id.tvChangePinStatus)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        tvNotesStatus = findViewById(R.id.tvNotesStatus)
        btnToggleNotes = findViewById(R.id.btnToggleNotes)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        tvAppVersion = findViewById(R.id.tvAppVersion)

        val pInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) { null }
        val verName = pInfo?.versionName ?: "?"
        val verCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pInfo?.longVersionCode ?: 2L
        } else {
            @Suppress("DEPRECATION")
            pInfo?.versionCode?.toLong() ?: 2L
        }
        tvAppVersion.text = "TV Diagnostics v$verName (Build $verCode)"

        val prefs = TVDiagnosticsApp.instance.preferences
        etServerUrl.setText(prefs.serverUrl)
        // Before unlocking (opened from the code prompt) only connection and update settings show
        if (VaultLock.session == null) {
            val body = findViewById<View>(R.id.etServerUrl).rootView
            hideVaultOnly(body)
        }

        VaultLock.session?.let { session ->
            findViewById<TextView>(R.id.tvMyCodeLabel).text = "Change My Code — ${session.userName} (6 Digits):"
        }
        tvUpdateStatus.text = "Installed: v$verName"
        // Found by the check at app start: offer it straight away
        AppUpdater.pendingUpdate?.takeIf { AppUpdater.isNewer(it.version, verName) }?.let { release ->
            availableUpdate = release
            tvUpdateStatus.text = "Installed: v$verName · v${release.version} available"
            btnCheckUpdate.text = "Update to v${release.version}"
        }

        setupUiModeRow()
        btnChangePin.setOnClickListener { changePin() }
        btnCheckUpdate.setOnClickListener { checkOrInstallUpdate() }

        val ip = getLocalIpAddress()
        tvDeviceIpHint.text = if (ip != null) {
            val subnet = ip.substringBeforeLast('.')
            "Device IP: $ip (Backend typically: http://$subnet.X:8090)"
        } else {
            "Default: http://tv-diagnostics:8090 (or http://10.0.2.2:8090 for emulator)"
        }

        updateNotesStatusUI()

        btnTestConnection.setOnClickListener {
            testServerConnection()
        }

        btnToggleNotes.setOnClickListener {
            if (prefs.isNotesEnabled) {
                prefs.isNotesEnabled = false
                updateNotesStatusUI()
                Toast.makeText(context, "Notes feature disabled", Toast.LENGTH_SHORT).show()
            } else {
                val pinDialog = NotesPinDialog(context) {
                    prefs.isNotesEnabled = true
                    updateNotesStatusUI()
                    Toast.makeText(context, "Notes feature unlocked successfully", Toast.LENGTH_SHORT).show()
                }
                pinDialog.show()
            }
        }

        val btnCancel = findViewById<Button>(R.id.btnSettingsCancel)
        val btnSave = findViewById<Button>(R.id.btnSettingsSave)

        listOf(btnTestConnection, btnChangePin, findViewById(R.id.btnChangeUiMode), btnCheckUpdate, btnToggleNotes, btnCancel, btnSave).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.06f else 1.0f)
                    .scaleY(if (hasFocus) 1.06f else 1.0f)
                    .setDuration(120)
                    .start()
            }
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnSave.setOnClickListener {
            val url = etServerUrl.text.toString().trim().trimEnd('/')
            if (url.isNotEmpty()) {
                prefs.serverUrl = url
            }
            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
            dismiss()
            onDismissCallback?.invoke()
        }

        // Set default D-pad focus to test connection button, avoiding popping virtual keyboard
        btnTestConnection.requestFocus()
    }

    /** Screen Layout row: switching rebuilds the current screen right away in the other layout. */
    private fun setupUiModeRow() {
        findViewById<TextView>(R.id.tvUiModeStatus).text =
            if (UiMode.isMobile) "Phone / tablet (touch screen)" else "TV (remote control)"
        findViewById<Button>(R.id.btnChangeUiMode).setOnClickListener {
            UiModeDialog(context, firstLaunch = false) { mode ->
                if (mode == UiMode.current) return@UiModeDialog
                UiMode.set(mode)
                dismiss()
                // Locked (opened from the code prompt): the rebuilt start screen asks for the code again
                if (VaultLock.session == null) VaultLock.promptOnReturn = true
                context.findActivity()?.recreate()
            }.show()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun testServerConnection() {
        val targetUrl = etServerUrl.text.toString().trim().trimEnd('/')
        if (targetUrl.isEmpty()) {
            tvConnectionStatus.text = "Please enter a server URL first"
            tvConnectionStatus.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
            tvConnectionStatus.visibility = View.VISIBLE
            return
        }

        tvConnectionStatus.text = "Probing $targetUrl..."
        tvConnectionStatus.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        tvConnectionStatus.visibility = View.VISIBLE
        btnTestConnection.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(4, TimeUnit.SECONDS)
                        .readTimeout(4, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder()
                        .url("$targetUrl/api/v1/status")
                        .get()
                        .build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        Gson().fromJson(body, ServerStatus::class.java)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            btnTestConnection.isEnabled = true
            if (result != null && result.status == "online") {
                tvConnectionStatus.text = "🟢 Connection Successful! Server online" + (result.version?.let { " (v$it)." } ?: ".")
                tvConnectionStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_green))
            } else {
                tvConnectionStatus.text = "🔴 Cannot connect to $targetUrl. Verify server IP, port, and Wi-Fi."
                tvConnectionStatus.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
            }
        }
    }

    private fun hideVaultOnly(view: View) {
        if (view.tag == "vaultOnly") {
            view.visibility = View.GONE
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) hideVaultOnly(view.getChildAt(i))
        }
    }

    private fun showChangePinStatus(message: String, ok: Boolean) {
        tvChangePinStatus.text = message
        tvChangePinStatus.setTextColor(ContextCompat.getColor(context, if (ok) R.color.accent_green else R.color.danger_red))
        tvChangePinStatus.visibility = View.VISIBLE
    }

    private fun changePin() {
        val newPin = etNewPin.text.toString().trim()
        val confirm = etConfirmPin.text.toString().trim()
        when {
            !newPin.matches(Regex("\\d{6}")) -> {
                showChangePinStatus("The code must be exactly 6 digits", ok = false)
                return
            }
            newPin != confirm -> {
                showChangePinStatus("The two codes don't match", ok = false)
                return
            }
        }
        btnChangePin.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val error = ApiClient.changePin(newPin)
            btnChangePin.isEnabled = true
            if (error == null) {
                etNewPin.setText("")
                etConfirmPin.setText("")
                showChangePinStatus("Code changed. Use the new code next time.", ok = true)
            } else {
                showChangePinStatus(error, ok = false)
            }
        }
    }

    /** First press checks GitHub; if a newer release exists the button becomes "Update to vX". */
    private fun checkOrInstallUpdate() {
        if (isUpdating) return
        val release = availableUpdate
        if (release != null) {
            downloadAndInstall(release)
            return
        }
        isUpdating = true
        btnCheckUpdate.isEnabled = false
        tvUpdateStatus.text = "Checking for updates..."
        CoroutineScope(Dispatchers.Main).launch {
            val latest = AppUpdater.fetchLatest()
            val current = AppUpdater.currentVersion(context)
            isUpdating = false
            btnCheckUpdate.isEnabled = true
            when {
                latest == null -> tvUpdateStatus.text = "Installed: v$current · update server unreachable"
                AppUpdater.isNewer(latest.version, current) -> {
                    availableUpdate = latest
                    tvUpdateStatus.text = "Installed: v$current · v${latest.version} available"
                    btnCheckUpdate.text = "Update to v${latest.version}"
                    btnCheckUpdate.requestFocus()
                }
                else -> tvUpdateStatus.text = "Installed: v$current · up to date"
            }
        }
    }

    private fun downloadAndInstall(release: AppUpdater.Release) {
        isUpdating = true
        btnCheckUpdate.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            AppUpdater.downloadAndInstall(context, release) { status -> tvUpdateStatus.text = status }
            isUpdating = false
            btnCheckUpdate.isEnabled = true
        }
    }

    private fun updateNotesStatusUI() {
        val prefs = TVDiagnosticsApp.instance.preferences
        if (prefs.isNotesEnabled) {
            tvNotesStatus.text = "Status: ON (Visible)"
            btnToggleNotes.text = "Disable Notes"
        } else {
            tvNotesStatus.text = "Status: OFF (Hidden)"
            btnToggleNotes.text = "Enable Notes (PIN)"
        }
    }
}
