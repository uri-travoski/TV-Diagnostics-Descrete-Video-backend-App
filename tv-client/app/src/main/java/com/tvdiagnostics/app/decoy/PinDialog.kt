package com.tvdiagnostics.app.decoy

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.ui.UiMode
import com.tvdiagnostics.app.data.ApiClient
import com.tvdiagnostics.app.security.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PinDialog(
    context: Context,
    private val onSuccess: (Session) -> Unit
) : Dialog(context) {

    private val pinBuilder = StringBuilder()
    private val pinBoxes = mutableListOf<TextView>()
    private val keypadButtons = mutableListOf<Button>()
    private lateinit var tvError: TextView
    private var isVerifying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(UiMode.keypadLayout(context, R.layout.dialog_pin, R.layout.dialog_pin_mobile))
        UiMode.fitDialog(this, 600)

        window?.setBackgroundDrawableResource(android.R.color.transparent)

        tvError = findViewById(R.id.tvPinError)

        pinBoxes.add(findViewById(R.id.pinBox1))
        pinBoxes.add(findViewById(R.id.pinBox2))
        pinBoxes.add(findViewById(R.id.pinBox3))
        pinBoxes.add(findViewById(R.id.pinBox4))
        pinBoxes.add(findViewById(R.id.pinBox5))
        pinBoxes.add(findViewById(R.id.pinBox6))

        findViewById<Button?>(R.id.btnPinCancel)?.let { cancelBtn ->
            cancelBtn.setOnClickListener {
                if (!isVerifying) dismiss()
            }
        }

        findViewById<Button?>(R.id.btnPinServer)?.setOnClickListener {
            if (!isVerifying) SettingsDialog(context).show()
        }

        setupKeypad()

        // Give initial D-pad focus to key 1
        findViewById<Button?>(R.id.btnKey1)?.let { key1 -> key1.post { key1.requestFocus() } }
    }

    private fun setupKeypad() {
        val keyMap = mapOf(
            R.id.btnKey0 to "0",
            R.id.btnKey1 to "1",
            R.id.btnKey2 to "2",
            R.id.btnKey3 to "3",
            R.id.btnKey4 to "4",
            R.id.btnKey5 to "5",
            R.id.btnKey6 to "6",
            R.id.btnKey7 to "7",
            R.id.btnKey8 to "8",
            R.id.btnKey9 to "9"
        )

        for ((id, num) in keyMap) {
            val btn = findViewById<Button>(id)
            keypadButtons.add(btn)
            btn.setOnClickListener {
                appendDigit(num)
            }
        }

        val btnBack = findViewById<Button>(R.id.btnKeyBack)
        keypadButtons.add(btnBack)
        btnBack.setOnClickListener {
            removeDigit()
        }

        val btnSubmit = findViewById<Button>(R.id.btnKeySubmit)
        keypadButtons.add(btnSubmit)
        btnSubmit.setOnClickListener {
            submitPin()
        }
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        keypadButtons.forEach { it.isEnabled = enabled }
        findViewById<Button?>(R.id.btnPinCancel)?.isEnabled = enabled
    }

    /** Too many wrong codes: the backend refuses codes from this TV for a while (an admin can lift it). */
    private fun showLocked(seconds: Int) {
        val minutes = (seconds + 59) / 60
        tvError.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
        tvError.text = if (minutes > 1) "Too many wrong codes. Try again in $minutes min" else "Too many wrong codes. Try again in a minute"
        tvError.visibility = View.VISIBLE
    }

    private fun appendDigit(d: String) {
        if (isVerifying) return
        if (pinBuilder.length < 6) {
            pinBuilder.append(d)
            updateDisplay()
            tvError.visibility = View.GONE
            if (pinBuilder.length == 6) {
                submitPin()
            }
        }
    }

    private fun removeDigit() {
        if (isVerifying) return
        if (pinBuilder.isNotEmpty()) {
            pinBuilder.deleteCharAt(pinBuilder.length - 1)
            updateDisplay()
            tvError.visibility = View.GONE
        }
    }

    private fun updateDisplay() {
        val len = pinBuilder.length
        for (i in 0 until 6) {
            pinBoxes[i].text = if (i < len) "●" else ""
        }
    }

    private fun submitPin() {
        if (isVerifying) return
        if (pinBuilder.length < 6) {
            tvError.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
            tvError.text = "Enter all 6 digits"
            tvError.visibility = View.VISIBLE
            return
        }

        isVerifying = true
        tvError.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        tvError.text = "Verifying..."
        tvError.visibility = View.VISIBLE
        setKeypadEnabled(false)

        val pin = pinBuilder.toString()
        CoroutineScope(Dispatchers.Main).launch {
            val result = ApiClient.verifyPin(pin)
            isVerifying = false
            setKeypadEnabled(true)
            if (result is ApiClient.PinResult.Success) {
                dismiss()
                onSuccess(result.session)
                return@launch
            }
            tvError.setTextColor(ContextCompat.getColor(context, R.color.danger_red))
            when (result) {
                is ApiClient.PinResult.Locked -> showLocked(result.seconds)
                is ApiClient.PinResult.Invalid -> {
                    val left = result.attemptsLeft
                    tvError.text = context.getString(R.string.pin_invalid) +
                        if (left != null) " · $left ${if (left == 1) "try" else "tries"} left" else ""
                }
                else -> tvError.text = "Diagnostic server unreachable"
            }
            tvError.visibility = View.VISIBLE
            pinBuilder.clear()
            updateDisplay()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVerifying) return true
        // Support direct TV remote numeric buttons (0-9)
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            val digit = (keyCode - KeyEvent.KEYCODE_0).toString()
            appendDigit(digit)
            return true
        } else if (keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
            val digit = (keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
            appendDigit(digit)
            return true
        } else if (keyCode == KeyEvent.KEYCODE_DEL) {
            removeDigit()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            submitPin()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
