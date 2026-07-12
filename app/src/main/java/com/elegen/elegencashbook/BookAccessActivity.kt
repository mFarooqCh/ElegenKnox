package com.elegen.elegencashbook

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elegen.elegencashbook.core.permission.Permission
import com.elegen.elegencashbook.feature.sharing.BookAccessUiEvent
import com.elegen.elegencashbook.feature.sharing.BookAccessUiState
import com.elegen.elegencashbook.feature.sharing.BookAccessViewModel
import com.elegen.elegencashbook.feature.sharing.GrantItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BookAccessActivity : AppCompatActivity() {

    private val viewModel: BookAccessViewModel by viewModels()
    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var shareButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_book_access)

        val rootView = findViewById<View>(R.id.book_access_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        intent.getStringExtra("book_name")?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.book_access_title).text = "Share \"$it\""
        }

        findViewById<ImageButton>(R.id.book_access_back).setOnClickListener { finish() }
        shareButton = findViewById(R.id.book_access_invite)
        shareButton.setOnClickListener { showShareSheet() }

        container = findViewById(R.id.book_access_container)
        emptyText = findViewById(R.id.book_access_empty)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: BookAccessUiState) {
        shareButton.visibility = if (state.canManage) View.VISIBLE else View.INVISIBLE
        emptyText.visibility = if (state.grants.isEmpty() && !state.loading) View.VISIBLE else View.GONE

        container.removeAllViews()
        state.grants.forEachIndexed { index, grant ->
            if (index > 0) container.addView(divider())
            val row = layoutInflater.inflate(R.layout.item_grant_row, container, false)
            row.findViewById<TextView>(R.id.grant_row_email).text = grant.emailOrName
            row.findViewById<TextView>(R.id.grant_row_subtitle).text = grant.accessLabel
            row.findViewById<TextView>(R.id.grant_row_access).text = grant.accessLabel.uppercase()
            val revokeButton = row.findViewById<ImageButton>(R.id.grant_row_revoke)
            revokeButton.visibility = if (state.canManage) View.VISIBLE else View.GONE
            revokeButton.setOnClickListener { viewModel.onEvent(BookAccessUiEvent.Revoke(grant.userUid)) }
            container.addView(row)
        }

        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.onEvent(BookAccessUiEvent.ErrorShown)
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(ContextCompat.getColor(this@BookAccessActivity, R.color.divider_light))
    }

    private fun showShareSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_share_book, null)
        dialog.setContentView(view)

        val contactInput = view.findViewById<TextInputEditText>(R.id.share_contact_input)
        val accessGroup = view.findViewById<RadioGroup>(R.id.share_access_group)
        val submitButton = view.findViewById<MaterialButton>(R.id.share_submit_button)

        view.findViewById<ImageButton>(R.id.close_share_sheet).setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            val contact = contactInput.text?.toString().orEmpty()
            if (contact.isBlank()) {
                Toast.makeText(this, "Enter an email or phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val perms = if (accessGroup.checkedRadioButtonId == R.id.share_access_viewer) {
                setOf(Permission.BOOK_VIEW)
            } else {
                setOf(Permission.BOOK_VIEW, Permission.TX_ADD, Permission.TX_EDIT, Permission.TX_DELETE)
            }
            viewModel.onEvent(BookAccessUiEvent.Share(contact, perms))
            dialog.dismiss()
        }
        dialog.show()
    }
}
