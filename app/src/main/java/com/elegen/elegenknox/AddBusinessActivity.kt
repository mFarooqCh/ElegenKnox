package com.elegen.elegencashbook

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class AddBusinessActivity : AppCompatActivity() {
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
        nextButton.setOnClickListener { finish() }
    }
}
