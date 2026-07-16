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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elegen.elegencashbook.feature.main.MainUiEvent
import com.elegen.elegencashbook.feature.main.MainUiState
import com.elegen.elegencashbook.feature.main.MainViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var uiState = MainUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        val rootView = findViewById<View>(R.id.settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<BottomNavigationView>(R.id.settings_bottom_navigation).apply {
            selectedItemId = R.id.nav_settings
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_cashbook -> { finish(); false }
                    R.id.nav_help -> { startActivity(Intent(this@SettingsActivity, HelpActivity::class.java)); false }
                    else -> true
                }
            }
        }

        findViewById<LinearLayout>(R.id.row_business_settings).setOnClickListener {
            val business = uiState.activeBusiness
            if (business == null) {
                Toast.makeText(this, "Select a business first", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(this, BusinessSettingsActivity::class.java)
                        .putExtra("business_id", business.id)
                        .putExtra("business_name", business.name)
                )
            }
        }
        findViewById<LinearLayout>(R.id.row_app_settings).setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.row_your_profile).setOnClickListener {
            if (uiState.account.loggedIn) startActivity(Intent(this, ProfileActivity::class.java))
            else showAccountSheet()
        }
        findViewById<LinearLayout>(R.id.row_about).setOnClickListener { showAboutDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MainUiState) {
        uiState = state
        findViewById<TextView>(R.id.row_your_profile_subtitle).text =
            if (state.account.loggedIn) state.account.email ?: state.account.label else "Sign in to sync across devices"
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About CashBook")
            .setMessage("ElegenCashBook v${BuildConfig.VERSION_NAME}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAccountSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_account, null)
        val account = uiState.account

        val identity = view.findViewById<View>(R.id.account_identity)
        val guestHint = view.findViewById<TextView>(R.id.account_guest_hint)
        val btnLogin = view.findViewById<MaterialButton>(R.id.btn_account_login)
        val btnLogout = view.findViewById<MaterialButton>(R.id.btn_account_logout)
        val btnLogoutWipe = view.findViewById<MaterialButton>(R.id.btn_account_logout_wipe)

        if (account.loggedIn) {
            identity.visibility = View.VISIBLE
            btnLogout.visibility = View.VISIBLE
            btnLogoutWipe.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.account_email).text = account.email ?: account.label
            view.findViewById<TextView>(R.id.account_phone).text = account.phone ?: "No phone added"

            btnLogout.setOnClickListener {
                viewModel.onEvent(MainUiEvent.SignOutKeepData)
                dialog.dismiss()
                startActivity(Intent(this, LoginActivity::class.java))
            }
            btnLogoutWipe.setOnClickListener {
                dialog.dismiss()
                confirmWipe()
            }
        } else {
            guestHint.visibility = View.VISIBLE
            btnLogin.visibility = View.VISIBLE
            btnLogin.setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this, LoginActivity::class.java))
            }
        }

        view.findViewById<ImageButton>(R.id.close_account_sheet).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("Remove all local data?")
            .setMessage("This signs you out and deletes every business, book and entry stored on this device. This cannot be undone here.")
            .setPositiveButton("Delete everything") { _, _ ->
                viewModel.onEvent(MainUiEvent.SignOutWipeData)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
