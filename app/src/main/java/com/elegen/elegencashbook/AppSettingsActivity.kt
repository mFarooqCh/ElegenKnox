package com.elegen.elegencashbook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import com.elegen.elegencashbook.data.local.prefs.ThemeMode
import com.elegen.elegencashbook.ui.PickTargetDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elegen.elegencashbook.data.local.prefs.AppPreferences
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AppSettingsActivity : AppCompatActivity() {

    @Inject lateinit var appPreferences: AppPreferences

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(this, if (granted) "Notifications enabled" else "Notifications denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_app_settings)

        val rootView = findViewById<View>(R.id.app_settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.app_settings_back).setOnClickListener { finish() }

        val comingSoon = { Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show() }
        findViewById<LinearLayout>(R.id.row_data_backup).setOnClickListener { comingSoon() }
        findViewById<LinearLayout>(R.id.row_language).setOnClickListener { comingSoon() }

        findViewById<MaterialButton>(R.id.btn_enable_notifications).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) Toast.makeText(this, "Notifications already enabled", Toast.LENGTH_SHORT).show()
                else requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                comingSoon()
            }
        }

        // Not yet backed by real behavior — toggling just acknowledges, same as the other stub rows.
        listOf(
            R.id.switch_app_lock,
            R.id.switch_group_book_notifications,
            R.id.switch_amount_field_calculator,
        ).forEach { id ->
            findViewById<SwitchCompat>(id).setOnCheckedChangeListener { view, _ ->
                if (view.isPressed) comingSoon()
            }
        }

        val themeValue = findViewById<TextView>(R.id.theme_value)
        findViewById<LinearLayout>(R.id.row_theme).setOnClickListener { showThemePicker() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appPreferences.themeMode.collect { themeValue.text = it.label }
            }
        }
    }

    private fun showThemePicker() {
        val current = ThemeMode.entries.firstOrNull { it.nightMode == AppCompatDelegate.getDefaultNightMode() }
            ?: ThemeMode.SYSTEM
        PickTargetDialog.show(
            context = this,
            iconRes = R.drawable.ic_moon,
            headerTitle = "Theme",
            headerSubtitle = "Choose how the app looks",
            items = ThemeMode.entries.map {
                val icon = when (it) {
                    ThemeMode.SYSTEM -> R.drawable.ic_theme_system
                    ThemeMode.LIGHT -> R.drawable.ic_sun
                    ThemeMode.DARK -> R.drawable.ic_moon
                }
                PickTargetDialog.Item(it.key, it.label, it.desc, icon)
            },
            selectedId = current.key,
        ) { item ->
            val mode = ThemeMode.fromKey(item.id)
            lifecycleScope.launch { appPreferences.setThemeMode(mode) }
            AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        }
    }
}
