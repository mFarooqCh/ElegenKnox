package com.elegen.elegencashbook

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_help)

        val rootView = findViewById<View>(R.id.help_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<BottomNavigationView>(R.id.help_bottom_navigation).apply {
            selectedItemId = R.id.nav_help
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_cashbook -> { finish(); false }
                    R.id.nav_settings -> { startActivity(Intent(this@HelpActivity, SettingsActivity::class.java)); false }
                    else -> true
                }
            }
        }
    }
}
