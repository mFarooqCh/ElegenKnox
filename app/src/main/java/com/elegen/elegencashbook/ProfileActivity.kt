package com.elegen.elegencashbook

import android.os.Bundle
import android.view.View
import android.widget.EditText
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
import com.elegen.elegencashbook.feature.main.AccountUi
import com.elegen.elegencashbook.feature.profile.ProfileViewModel
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileActivity : AppCompatActivity() {

    private val viewModel: ProfileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)

        val rootView = findViewById<View>(R.id.profile_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.profile_back).setOnClickListener { finish() }

        val comingSoon = { Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show() }
        findViewById<LinearLayout>(R.id.row_change_photo).setOnClickListener { comingSoon() }
        findViewById<TextView>(R.id.btn_change_phone).setOnClickListener { comingSoon() }
        findViewById<TextView>(R.id.btn_change_email).setOnClickListener { comingSoon() }
        findViewById<MaterialButton>(R.id.btn_save_changes).setOnClickListener { comingSoon() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.account.collect { render(it) }
            }
        }
    }

    private fun render(account: AccountUi) {
        val name = account.label
        findViewById<EditText>(R.id.input_full_name).setText(name)
        findViewById<TextView>(R.id.profile_avatar_initials).text = initialsOf(name)

        findViewById<EditText>(R.id.input_phone).apply {
            setText(account.phone ?: "")
            hint = "Not added"
        }
        findViewById<LinearLayout>(R.id.phone_unverified_banner).visibility =
            if (account.phone.isNullOrBlank()) View.GONE else View.VISIBLE

        val hasEmail = !account.email.isNullOrBlank()
        findViewById<EditText>(R.id.input_email).setText(account.email ?: "")
        findViewById<LinearLayout>(R.id.email_verified_banner).visibility =
            if (hasEmail) View.VISIBLE else View.GONE
    }

    private fun initialsOf(name: String): String =
        name.trim().split(" ").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }
}
