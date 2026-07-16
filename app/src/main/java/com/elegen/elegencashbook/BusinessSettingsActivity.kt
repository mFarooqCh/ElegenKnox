package com.elegen.elegencashbook

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elegen.elegencashbook.core.ui.setPrimaryEnabled
import com.elegen.elegencashbook.feature.business.BusinessSettingsUiEvent
import com.elegen.elegencashbook.feature.business.BusinessSettingsUiState
import com.elegen.elegencashbook.feature.business.BusinessSettingsViewModel
import com.elegen.elegencashbook.ui.DeleteConfirmDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BusinessSettingsActivity : AppCompatActivity() {

    private val viewModel: BusinessSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_business_settings)

        val rootView = findViewById<View>(R.id.business_settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.business_settings_back).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.row_members).setOnClickListener {
            startActivity(
                Intent(this, MembersActivity::class.java)
                    .putExtra("business_id", intent.getStringExtra("business_id"))
                    .putExtra("business_name", intent.getStringExtra("business_name"))
            )
        }

        findViewById<LinearLayout>(R.id.row_business_profile).setOnClickListener { showRenameSheet() }
        findViewById<LinearLayout>(R.id.row_delete_business).setOnClickListener { confirmDeleteBusiness() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: BusinessSettingsUiState) {
        findViewById<TextView>(R.id.business_settings_title)?.text = state.businessName
        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.onEvent(BusinessSettingsUiEvent.ErrorShown)
        }
        if (state.deleted) {
            Toast.makeText(this, "Business deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showRenameSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_book, null)
        dialog.setContentView(view)

        val title = view.findViewById<TextView>(R.id.book_sheet_title)
        val nameLabel = view.findViewById<TextView>(R.id.book_name_label)
        val nameInput = view.findViewById<TextInputEditText>(R.id.book_name_input)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.book_name_layout)
        val saveButton = view.findViewById<MaterialButton>(R.id.add_book_button)
        val closeButton = view.findViewById<ImageButton>(R.id.close_book_sheet)
        view.findViewById<View>(R.id.book_suggestions_label).visibility = View.GONE
        view.findViewById<View>(R.id.book_suggestions).visibility = View.GONE

        title.text = "Business Profile"
        nameLabel.text = "Business Name"
        saveButton.text = "SAVE"
        nameInput.setText(viewModel.state.value.businessName)
        nameInput.setSelection(nameInput.text?.length ?: 0)
        saveButton.setPrimaryEnabled(true)
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                saveButton.setPrimaryEnabled(hasText)
                if (hasText) nameLayout.error = null
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        closeButton.setOnClickListener { dialog.dismiss() }
        saveButton.setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                nameLayout.error = "Business name required"
                return@setOnClickListener
            }
            viewModel.onEvent(BusinessSettingsUiEvent.Rename(name))
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun confirmDeleteBusiness() {
        DeleteConfirmDialog.show(
            context = this,
            title = "Delete this business?",
            subtitle = "All books and entries inside will be permanently deleted. This can't be undone.",
        ) {
            viewModel.onEvent(BusinessSettingsUiEvent.Delete)
        }
    }
}
