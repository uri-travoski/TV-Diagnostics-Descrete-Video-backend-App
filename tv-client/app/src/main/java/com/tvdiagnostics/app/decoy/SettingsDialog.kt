package com.tvdiagnostics.app.decoy

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.data.ServerStatus
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
    private lateinit var tvNotesStatus: TextView
    private lateinit var btnToggleNotes: Button
    private lateinit var tvAutoLockStatus: TextView
    private lateinit var btnToggleAutoLock: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnTestConnection: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings)

        window?.setBackgroundDrawableResource(android.R.color.transparent)

        etServerUrl = findViewById(R.id.etServerUrl)
        tvNotesStatus = findViewById(R.id.tvNotesStatus)
        btnToggleNotes = findViewById(R.id.btnToggleNotes)
        tvAutoLockStatus = findViewById(R.id.tvAutoLockStatus)
        btnToggleAutoLock = findViewById(R.id.btnToggleAutoLock)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        btnTestConnection = findViewById(R.id.btnTestConnection)

        val prefs = TVDiagnosticsApp.instance.preferences
        etServerUrl.setText(prefs.serverUrl)

        updateNotesStatusUI()
        updateAutoLockStatusUI()

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

        btnToggleAutoLock.setOnClickListener {
            prefs.isAutoLockEnabled = !prefs.isAutoLockEnabled
            updateAutoLockStatusUI()
            val msg = if (prefs.isAutoLockEnabled) "Auto-lock enabled (15m pause / 5m end)" else "Auto-lock disabled"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSettingsCancel).setOnClickListener {
            dismiss()
        }

        findViewById<Button>(R.id.btnSettingsSave).setOnClickListener {
            val url = etServerUrl.text.toString().trim().trimEnd('/')
            if (url.isNotEmpty()) {
                prefs.serverUrl = url
                Toast.makeText(context, "Settings saved: $url", Toast.LENGTH_SHORT).show()
            }
            dismiss()
            onDismissCallback?.invoke()
        }
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
                tvConnectionStatus.text = "🟢 Connection Successful! Server online with ${result.videoCount} indexed videos."
                tvConnectionStatus.setTextColor(ContextCompat.getColor(context, R.color.accent_green))
            } else {
                tvConnectionStatus.text = "🔴 Cannot connect to $targetUrl. Verify server IP, port, and Wi-Fi."
                tvConnectionStatus.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
            }
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

    private fun updateAutoLockStatusUI() {
        val prefs = TVDiagnosticsApp.instance.preferences
        if (prefs.isAutoLockEnabled) {
            tvAutoLockStatus.text = "Status: Enabled (15m / 5m)"
            btnToggleAutoLock.text = "Disable"
        } else {
            tvAutoLockStatus.text = "Status: Disabled"
            btnToggleAutoLock.text = "Enable"
        }
    }
}
