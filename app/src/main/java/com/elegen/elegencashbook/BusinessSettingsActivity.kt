package com.elegen.elegencashbook

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BusinessSettingsActivity : AppCompatActivity() {

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

        val comingSoon = { Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show() }
        findViewById<LinearLayout>(R.id.row_business_profile).setOnClickListener { comingSoon() }
        findViewById<LinearLayout>(R.id.row_change_primary_admin).setOnClickListener { comingSoon() }
        findViewById<LinearLayout>(R.id.row_delete_business).setOnClickListener { comingSoon() }
    }
}
