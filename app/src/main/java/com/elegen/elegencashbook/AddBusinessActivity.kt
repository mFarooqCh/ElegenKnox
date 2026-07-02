package com.elegen.elegencashbook

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
        setContentView(R.layout.activity_add_business)

        val businessInput = findViewById<TextInputEditText>(R.id.business_name_input)
        val nextButton = findViewById<MaterialButton>(R.id.next_button)
        val backButton = findViewById<android.widget.ImageButton>(R.id.back_button)

        nextButton.isEnabled = false
        businessInput.doAfterTextChanged { text ->
            nextButton.isEnabled = !text.isNullOrBlank()
        }

        backButton.setOnClickListener { finish() }
        nextButton.setOnClickListener {
            val name = businessInput.text?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) viewModel.onEvent(AddBusinessUiEvent.Create(name))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    nextButton.isEnabled = !state.saving && !businessInput.text.isNullOrBlank()
                    if (state.done) finish()
                }
            }
        }
    }
}
