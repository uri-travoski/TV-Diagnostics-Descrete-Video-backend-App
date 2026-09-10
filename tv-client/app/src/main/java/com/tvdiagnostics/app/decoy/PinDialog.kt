package com.tvdiagnostics.app.decoy

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.TextView
import com.tvdiagnostics.app.R
import com.tvdiagnostics.app.data.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PinDialog(
    context: Context,
    private val onSuccess: () -> Unit
) : Dialog(context) {

    private val pinBuilder = StringBuilder()
    private val pinBoxes = mutableListOf<TextView>()
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_pin)

        window?.setBackgroundDrawableResource(android.R.color.transparent)

        tvError = findViewById(R.id.tvPinError)

        pinBoxes.add(findViewById(R.id.pinBox1))
        pinBoxes.add(findViewById(R.id.pinBox2))
        pinBoxes.add(findViewById(R.id.pinBox3))
        pinBoxes.add(findViewById(R.id.pinBox4))
        pinBoxes.add(findViewById(R.id.pinBox5))
        pinBoxes.add(findViewById(R.id.pinBox6))

        findViewById<Button?>(R.id.btnPinCancel)?.setOnClickListener {
            dismiss()
        }

        setupKeypad()

        // Give initial D-pad focus to key 1
        findViewById<Button?>(R.id.btnKey1)?.requestFocus()
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
            findViewById<Button>(id).setOnClickListener {
                appendDigit(num)
            }
        }

        findViewById<Button>(R.id.btnKeyBack).setOnClickListener {
            removeDigit()
        }

        findViewById<Button>(R.id.btnKeySubmit).setOnClickListener {
            submitPin()
        }
    }

    private fun appendDigit(d: String) {
        if (pinBuilder.length < 6) {
            pinBuilder.append(d)
            updateDisplay()
            if (pinBuilder.length == 6) {
                submitPin()
            }
        }
    }

    private fun removeDigit() {
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
        if (pinBuilder.length != 6) {
            tvError.text = "Enter 6 digits"
            tvError.visibility = View.VISIBLE
            return
        }

        val pin = pinBuilder.toString()
        CoroutineScope(Dispatchers.Main).launch {
            val isValid = ApiClient.verifyPin(pin)
            if (isValid) {
                dismiss()
                onSuccess()
            } else {
                tvError.text = context.getString(R.string.pin_invalid)
                tvError.visibility = View.VISIBLE
                pinBuilder.clear()
                updateDisplay()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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
