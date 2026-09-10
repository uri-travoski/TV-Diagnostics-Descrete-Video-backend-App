package com.tvdiagnostics.app.decoy

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.vault.VaultActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

class DecoyActivity : AppCompatActivity() {

    private lateinit var tvDisplayRes: TextView
    private lateinit var tvRefreshRate: TextView
    private lateinit var tvIpAddr: TextView
    private lateinit var tvPingStatus: TextView
    private lateinit var tvLogOutput: TextView

    // Secret remote sequence detector: UP -> UP -> DOWN -> DOWN -> LEFT -> RIGHT
    private val secretSequence = listOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT
    )
    private val keyHistory = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decoy)

        initViews()
        detectDisplaySpecs()
        detectNetworkSpecs()
        setupListeners()

        if (intent.getBooleanExtra(EXTRA_AUTO_PROMPT_PIN, false)) {
            showPinDialog()
        } else {
            findViewById<Button>(R.id.btnScreenTest)?.requestFocus()
        }
    }

    private fun initViews() {
        tvDisplayRes = findViewById(R.id.tvDisplayRes)
        tvRefreshRate = findViewById(R.id.tvRefreshRate)
        tvIpAddr = findViewById(R.id.tvIpAddr)
        tvPingStatus = findViewById(R.id.tvPingStatus)
        tvLogOutput = findViewById(R.id.tvLogOutput)
    }

    private fun detectDisplaySpecs() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        val width = dm.widthPixels
        val height = dm.heightPixels
        val rate = windowManager.defaultDisplay.refreshRate

        tvDisplayRes.text = "$width x $height"
        tvRefreshRate.text = String.format("%.2f Hz", rate)
    }

    private fun detectNetworkSpecs() {
        val ip = getLocalIpAddress()
        tvIpAddr.text = ip ?: "Disconnected"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
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

    private fun setupListeners() {
        val btnScreenTest = findViewById<Button>(R.id.btnScreenTest)
        val btnPingTest = findViewById<Button>(R.id.btnPingTest)
        val btnServerCode = findViewById<Button>(R.id.btnServerCode)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        listOf(btnScreenTest, btnPingTest, btnServerCode, btnSettings).forEach { btn ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.05f else 1.0f)
                    .scaleY(if (hasFocus) 1.05f else 1.0f)
                    .setDuration(120)
                    .start()
            }
        }

        btnScreenTest.setOnClickListener {
            startActivity(Intent(this, ScreenTestActivity::class.java))
        }

        btnPingTest.setOnClickListener {
            runPingTest()
        }

        btnServerCode.setOnClickListener {
            showPinDialog()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun runPingTest() {
        tvPingStatus.text = "Testing latency..."
        tvLogOutput.text = "[Probe Active] Sending ICMP ping to DNS 8.8.8.8...\n"

        CoroutineScope(Dispatchers.IO).launch {
            val start = System.currentTimeMillis()
            var reachable = false
            try {
                val address = InetAddress.getByName("8.8.8.8")
                reachable = address.isReachable(3000)
            } catch (e: Exception) {
                // Ignore
            }
            val elapsed = System.currentTimeMillis() - start

            withContext(Dispatchers.Main) {
                if (reachable) {
                    tvPingStatus.text = "${elapsed} ms (Healthy)"
                    tvLogOutput.append("Response from 8.8.8.8: time=${elapsed}ms TTL=56\nPacket Loss: 0%\nStatus: Online & Ready")
                } else {
                    tvPingStatus.text = "Gateway Timeout"
                    tvLogOutput.append("Local Gateway Probe timed out.\nCheck network router settings.")
                }
            }
        }
    }

    private fun showPinDialog() {
        val dialog = PinDialog(this) {
            TVDiagnosticsApp.instance.preferences.isVaultUnlocked = true
            val intent = Intent(this, VaultActivity::class.java)
            startActivity(intent)
        }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialog(this)
        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Track secret remote sequence
        keyHistory.add(keyCode)
        if (keyHistory.size > secretSequence.size) {
            keyHistory.removeAt(0)
        }

        if (keyHistory == secretSequence) {
            keyHistory.clear()
            showPinDialog()
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_AUTO_PROMPT_PIN, false) == true) {
            showPinDialog()
        }
    }

    companion object {
        const val EXTRA_AUTO_PROMPT_PIN = "extra_auto_prompt_pin"
    }
}
