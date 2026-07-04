package com.elegen.elegencashbook

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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

            when {
                state.error != null -> {
                    tvError.visibility = View.VISIBLE
                    tvError.setTextColor(android.graphics.Color.parseColor("#DC2626"))
                    tvError.text = state.error
                }
                state.info != null -> {
                    tvError.visibility = View.VISIBLE
                    tvError.setTextColor(android.graphics.Color.parseColor("#16A34A"))
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
}
