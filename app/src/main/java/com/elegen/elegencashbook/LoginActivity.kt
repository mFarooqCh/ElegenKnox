package com.elegen.elegencashbook

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elegen.elegencashbook.feature.auth.AuthMode
import com.elegen.elegencashbook.feature.auth.LoginUiEvent
import com.elegen.elegencashbook.feature.auth.LoginUiState
import com.elegen.elegencashbook.feature.auth.LoginViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val title = findViewById<TextView>(R.id.login_title)
        val subtitle = findViewById<TextView>(R.id.login_subtitle)
        val layoutName = findViewById<TextInputLayout>(R.id.layout_display_name)
        val layoutPhone = findViewById<TextInputLayout>(R.id.layout_phone)
        val etName = findViewById<TextInputEditText>(R.id.et_display_name)
        val etEmail = findViewById<TextInputEditText>(R.id.et_email)
        val etPhone = findViewById<TextInputEditText>(R.id.et_phone)
        val etPassword = findViewById<TextInputEditText>(R.id.et_password)
        val tvForgotPassword = findViewById<TextView>(R.id.tv_forgot_password)
        val tvError = findViewById<TextView>(R.id.tv_error)
        val btnPrimary = findViewById<MaterialButton>(R.id.btn_primary)
        val btnSwitch = findViewById<MaterialButton>(R.id.btn_switch_mode)
        val btnGuest = findViewById<MaterialButton>(R.id.btn_guest)
        val tvServerNote = findViewById<TextView>(R.id.tv_server_note)

        btnPrimary.setOnClickListener {
            viewModel.onEvent(
                LoginUiEvent.Submit(
                    email = etEmail.text?.toString().orEmpty(),
                    password = etPassword.text?.toString().orEmpty(),
                    displayName = etName.text?.toString().orEmpty(),
                    phone = etPhone.text?.toString().orEmpty(),
                )
            )
        }
        btnSwitch.setOnClickListener { viewModel.onEvent(LoginUiEvent.SwitchMode) }
        btnGuest.setOnClickListener { viewModel.onEvent(LoginUiEvent.ContinueAsGuest) }
        tvForgotPassword.setOnClickListener { showForgotPasswordDialog(etEmail.text?.toString().orEmpty()) }

        fun render(state: LoginUiState) {
            if (state.done) { finish(); return }

            val registering = state.mode == AuthMode.REGISTER
            title.text = if (registering) "Create your account" else "Welcome back"
            subtitle.text = if (registering) "Register to sync and share your books"
                            else "Sign in to sync and share your books"
            layoutName.visibility = if (registering) View.VISIBLE else View.GONE
            layoutPhone.visibility = if (registering) View.VISIBLE else View.GONE
            btnPrimary.text = when {
                state.loading -> "Please wait…"
                registering -> "Create account"
                else -> "Sign in"
            }
            btnPrimary.isEnabled = !state.loading && state.serverConfigured
            btnSwitch.text = if (registering) "Have an account? Sign in" else "New here? Create an account"
            btnSwitch.isEnabled = state.serverConfigured
            tvForgotPassword.visibility = if (registering) View.GONE else View.VISIBLE
            tvForgotPassword.isEnabled = !state.resetLoading && state.serverConfigured

            when {
                state.error != null -> {
                    tvError.visibility = View.VISIBLE
                    tvError.setTextColor(ContextCompat.getColor(this, R.color.danger_red))
                    tvError.text = state.error
                }
                state.info != null -> {
                    tvError.visibility = View.VISIBLE
                    tvError.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    tvError.text = state.info
                }
                else -> tvError.visibility = View.GONE
            }

            if (!state.serverConfigured) {
                tvServerNote.text = "Sync server not configured — you can use the app fully offline."
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    /** Step 1: email → code emailed. Step 2: code + new password → verified & signed in (render() then navigates to Main via state.done). */
    private fun showForgotPasswordDialog(prefillEmail: String) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        fun marginTop() = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = padding / 2 }

        val emailInput = TextInputEditText(this).apply {
            setText(prefillEmail)
            hint = "Email"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val codeInput = TextInputEditText(this).apply {
            hint = "6-digit code from email"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val newPasswordInput = TextInputEditText(this).apply {
            hint = "New password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmPasswordInput = TextInputEditText(this).apply {
            hint = "Confirm new password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val step2Fields = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
            addView(codeInput)
            addView(newPasswordInput, marginTop())
            addView(confirmPasswordInput, marginTop())
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(emailInput)
            addView(step2Fields, marginTop())
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Reset password")
            .setMessage("Enter your account email — we'll send a 6-digit code.")
            .setView(container)
            .setPositiveButton("Send code", null)
            .setNegativeButton("Cancel", null)
            .create()

        var codeSent = false
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!codeSent) {
                    viewModel.onEvent(LoginUiEvent.ForgotPassword(emailInput.text?.toString().orEmpty()))
                } else {
                    viewModel.onEvent(
                        LoginUiEvent.ResetPasswordWithCode(
                            email = emailInput.text?.toString().orEmpty(),
                            code = codeInput.text?.toString().orEmpty(),
                            newPassword = newPasswordInput.text?.toString().orEmpty(),
                            confirmPassword = confirmPasswordInput.text?.toString().orEmpty(),
                        )
                    )
                }
            }
        }

        val job = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !state.resetLoading
                    if (!codeSent && state.resetCodeSent) {
                        codeSent = true
                        emailInput.isEnabled = false
                        step2Fields.visibility = View.VISIBLE
                        dialog.setTitle("Enter code & new password")
                        dialog.setMessage("Check your email for the 6-digit code.")
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Reset password"
                    }
                    state.error?.let {
                        android.widget.Toast.makeText(this@LoginActivity, it, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    if (state.done) dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            job.cancel()
            viewModel.onEvent(LoginUiEvent.ResetFlowDismissed)
        }
        dialog.show()
    }
}
