package com.elegen.elegencashbook

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elegen.elegencashbook.feature.business.AddBusinessUiEvent
import com.elegen.elegencashbook.feature.business.AddBusinessViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddBusinessActivity : AppCompatActivity() {

    private val viewModel: AddBusinessViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_add_business)

        val contentRoot = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val businessInput = findViewById<TextInputEditText>(R.id.business_name_input)
        val nextButton = findViewById<MaterialButton>(R.id.next_button)
        val backButton = findViewById<android.widget.ImageButton>(R.id.back_button)

        setNextButtonStyle(nextButton, enabled = false)
        businessInput.doAfterTextChanged { text ->
            setNextButtonStyle(nextButton, enabled = !text.isNullOrBlank())
        }

        backButton.setOnClickListener { finish() }
        nextButton.setOnClickListener {
            val name = businessInput.text?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) viewModel.onEvent(AddBusinessUiEvent.Create(name))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    setNextButtonStyle(nextButton, enabled = !state.saving && !businessInput.text.isNullOrBlank())
                    if (state.done) finish()
                }
            }
        }
    }

    /** Same enabled/disabled palette as the "Add Book" button (bottom_sheet_add_book). */
    private fun setNextButtonStyle(button: MaterialButton, enabled: Boolean) {
        button.isEnabled = enabled
        if (enabled) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#2563EB"))
            button.setTextColor(android.graphics.Color.WHITE)
        } else {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#E5E7EB"))
            button.setTextColor(android.graphics.Color.parseColor("#6B7280"))
        }
    }
}
