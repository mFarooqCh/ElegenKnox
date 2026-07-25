package com.elegen.elegencashbook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
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
            // OWNER row is not manageable — they already have everything, no menu or edit sheet.
            val editable = state.canManage && !member.isRevoked && member.role != BusinessRole.OWNER
            val menuButton = row.findViewById<ImageButton>(R.id.member_row_menu)
            menuButton.visibility = if (editable) View.VISIBLE else View.GONE
            menuButton.setOnClickListener { showMemberActionSheet(member, menuButton) }
            row.setOnClickListener { if (editable) showInviteSheet(member) }
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

    /** [editing] non-null reuses this sheet to edit an existing member: contact locked, role + shared books pre-filled. */
    private fun showInviteSheet(editing: MemberItem? = null) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_invite_member, null)
        dialog.setContentView(view)

        val contactInput = view.findViewById<TextInputEditText>(R.id.invite_contact_input)
        val roleGroup = view.findViewById<RadioGroup>(R.id.invite_role_group)
        val submitButton = view.findViewById<MaterialButton>(R.id.invite_submit_button)
        val allBooksCheckbox = view.findViewById<CheckBox>(R.id.invite_all_books_checkbox)
        val booksScroll = view.findViewById<View>(R.id.invite_books_scroll)
        val booksList = view.findViewById<LinearLayout>(R.id.invite_books_list)

        val books = viewModel.state.value.books
        booksList.removeAllViews()
        val bookCheckboxes = books.map { book ->
            CheckBox(this).apply {
                text = book.name
                tag = book.id
                isChecked = editing != null && book.id in editing.grantedBookIds
                setTextColor(ContextCompat.getColor(this@MembersActivity, R.color.text_dark))
                textSize = 13f
                buttonTintList = ContextCompat.getColorStateList(this@MembersActivity, R.color.brand)
            }.also { booksList.addView(it) }
        }
        allBooksCheckbox.setOnCheckedChangeListener { _, checked ->
            booksScroll.visibility = if (checked) View.GONE else View.VISIBLE
        }

        if (editing != null) {
            contactInput.setText(editing.email)
            contactInput.isEnabled = false
            roleGroup.check(if (editing.role == BusinessRole.VIEWER) R.id.invite_role_viewer else R.id.invite_role_admin)
            allBooksCheckbox.isChecked = !editing.bookScoped
            booksScroll.visibility = if (editing.bookScoped) View.VISIBLE else View.GONE
            submitButton.text = "SAVE CHANGES"
        }

        view.findViewById<ImageButton>(R.id.close_invite_sheet).setOnClickListener { dialog.dismiss() }
        submitButton.setOnClickListener {
            val contact = if (editing != null) editing.email else contactInput.text?.toString().orEmpty()
            if (contact.isBlank()) {
                Toast.makeText(this, "Enter an email or phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val role = if (roleGroup.checkedRadioButtonId == R.id.invite_role_viewer) BusinessRole.VIEWER else BusinessRole.ADMIN
            val bookIds = if (allBooksCheckbox.isChecked) {
                null
            } else {
                bookCheckboxes.filter { it.isChecked }.map { it.tag as String }.also {
                    if (it.isEmpty()) {
                        Toast.makeText(this, "Select at least one book, or check \"All books\"", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
            }
            if (editing != null) {
                // Books unchecked since the sheet opened lose their explicit ALLOW grant (skip when
                // switching to All books — scoping is off then, so lingering grants are moot).
                val revoked = if (bookIds == null) emptyList()
                    else (editing.grantedBookIds - bookIds.toSet()).toList()
                viewModel.onEvent(MembersUiEvent.EditMember(contact, editing.userUid, role, bookIds, revoked))
            } else {
                viewModel.onEvent(MembersUiEvent.Invite(contact, role, bookIds))
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showMemberActionSheet(member: MemberItem, anchor: View) {
        val promote = member.role == BusinessRole.VIEWER
        val popupWidthPx = (200 * resources.displayMetrics.density).toInt()
        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_member_menu, null)
        val popupWindow = PopupWindow(popupView, popupWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        popupView.findViewById<TextView>(R.id.action_change_role_text).text = if (promote) "Make Admin" else "Make Viewer"
        popupView.findViewById<View>(R.id.action_change_role).setOnClickListener {
            popupWindow.dismiss()
            viewModel.onEvent(
                MembersUiEvent.ChangeRole(member.userUid, if (promote) BusinessRole.ADMIN else BusinessRole.VIEWER)
            )
        }
        popupView.findViewById<View>(R.id.action_remove_member).setOnClickListener {
            popupWindow.dismiss()
            viewModel.onEvent(MembersUiEvent.Revoke(member.userUid))
        }

        val xOffset = anchor.width - popupWidthPx
        popupWindow.showAsDropDown(anchor, xOffset, 4)
    }
}
