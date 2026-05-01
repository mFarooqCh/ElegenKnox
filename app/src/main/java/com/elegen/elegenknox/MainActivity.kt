package com.elegen.elegenknox

import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val codeInput = findViewById<EditText>(R.id.code_input)
        val unlockButton = findViewById<Button>(R.id.unlock_button)
        val feedbackText = findViewById<TextView>(R.id.feedback_text)

        codeInput.filters = arrayOf(InputFilter.LengthFilter(8))
        feedbackText.text = ""
        feedbackText.visibility = View.INVISIBLE

        unlockButton.setOnClickListener {
            val input = codeInput.text?.toString()?.trim().orEmpty()
            val feedbackResId = when {
                input.isEmpty() -> R.string.lock_empty
                input == "1234" -> R.string.lock_success
                else -> R.string.lock_error
            }

            feedbackText.setText(feedbackResId)
            feedbackText.visibility = View.VISIBLE
        }

        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}