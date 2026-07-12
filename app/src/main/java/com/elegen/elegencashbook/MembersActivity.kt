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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elegen.elegencashbook.core.permission.BusinessRole
import com.elegen.elegencashbook.feature.members.MemberItem
import com.elegen.elegencashbook.feature.members.MembersUiEvent
import com.elegen.elegencashbook.feature.members.MembersUiState
import com.elegen.elegencashbook.feature.members.MembersViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MembersActivity : AppCompatActivity() {

    private val viewModel: MembersViewModel by viewModels()
    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var inviteButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_members)

        val rootView = findViewById<View>(R.id.members_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        intent.getStringExtra("business_name")?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.members_title).text = it
        }

        findViewById<ImageButton>(R.id.members_back).setOnClickListener { finish() }
        inviteButton = findViewById(R.id.members_invite)
        inviteButton.setOnClickListener { showInviteSheet() }

        container = findViewById(R.id.members_container)
        emptyText = findViewById(R.id.members_empty)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MembersUiState) {
        inviteButton.visibility = if (state.canManage) View.VISIBLE else View.INVISIBLE
        emptyText.visibility = if (state.members.isEmpty() && !state.loading) View.VISIBLE else View.GONE

        container.removeAllViews()
        state.members.forEachIndexed { index, member ->
            if (index > 0) container.addView(divider())
            val row = layoutInflater.inflate(R.layout.item_member_row, container, false)
            row.findViewById<TextView>(R.id.member_row_email).text = member.emailOrName
            row.findViewById<TextView>(R.id.member_row_subtitle).text =
                if (member.isRevoked) "Access revoked" else "Business member"
            row.findViewById<TextView>(R.id.member_row_role).text = member.roleLabel.uppercase()
            val menuButton = row.findViewById<ImageButton>(R.id.member_row_menu)
            menuButton.visibility = if (state.canManage && !member.isRevoked) View.VISIBLE else View.GONE
            menuButton.setOnClickListener { showMemberActionSheet(member) }
            container.addView(row)
        }

        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.onEvent(MembersUiEvent.ErrorShown)
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(ContextCompat.getColor(this@MembersActivity, R.color.divider_light))
    }

    private fun showInviteSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_invite_member, null)
        dialog.setContentView(view)

        val contactInput = view.findViewById<TextInputEditText>(R.id.invite_contact_input)
        val roleGroup = view.findViewById<RadioGroup>(R.id.invite_role_group)
        val submitButton = view.findViewById<MaterialButton>(R.id.invite_submit_button)

        view.findViewById<ImageButton>(R.id.close_invite_sheet).setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            val contact = contactInput.text?.toString().orEmpty()
            if (contact.isBlank()) {
                Toast.makeText(this, "Enter an email or phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val role = if (roleGroup.checkedRadioButtonId == R.id.invite_role_viewer) BusinessRole.VIEWER else BusinessRole.ADMIN
            viewModel.onEvent(MembersUiEvent.Invite(contact, role))
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showMemberActionSheet(member: MemberItem) {
        val promote = member.role == BusinessRole.VIEWER
        val items = arrayOf(if (promote) "Make Admin" else "Make Viewer", "Remove from business")
        AlertDialog.Builder(this)
            .setTitle(member.emailOrName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> viewModel.onEvent(
                        MembersUiEvent.ChangeRole(member.userUid, if (promote) BusinessRole.ADMIN else BusinessRole.VIEWER)
                    )
                    1 -> viewModel.onEvent(MembersUiEvent.Revoke(member.userUid))
                }
            }
            .show()
    }
}
