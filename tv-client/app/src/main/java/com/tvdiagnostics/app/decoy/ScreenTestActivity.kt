package com.tvdiagnostics.app.decoy

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tvdiagnostics.app.R

class ScreenTestActivity : AppCompatActivity() {

    private val colors = listOf(
        Color.BLACK,
        Color.WHITE,
        Color.RED,
        Color.GREEN,
        Color.BLUE,
        Color.DKGRAY
    )
    private var colorIndex = 0

    private lateinit var container: FrameLayout
    private lateinit var hintText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_test)

        container = findViewById(R.id.screenContainer)
        hintText = findViewById(R.id.tvScreenHint)

        updateColor()

        container.setOnClickListener {
            cycleColor()
        }
    }

    private fun cycleColor() {
        colorIndex = (colorIndex + 1) % colors.size
        updateColor()
        hintText.visibility = View.GONE
    }

    private fun updateColor() {
        container.setBackgroundColor(colors[colorIndex])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> {
                cycleColor()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                val intent = Intent(this, DecoyActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
