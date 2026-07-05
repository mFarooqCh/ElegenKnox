package com.elegen.elegencashbook

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.elegen.elegencashbook.feature.entry.EntryDetailsUiEvent
import com.elegen.elegencashbook.feature.entry.EntryDetailsUiState
import com.elegen.elegencashbook.feature.entry.EntryDetailsViewModel
import com.elegen.elegencashbook.ui.DeleteConfirmDialog
import com.elegen.elegencashbook.ui.EntryFormDialog
import com.elegen.elegencashbook.ui.PickTargetDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EntryDetailsActivity : AppCompatActivity() {

    private val viewModel: EntryDetailsViewModel by viewModels()

    private lateinit var accentStrip: View
    private lateinit var typeChip: TextView
    private lateinit var dateTimeText: TextView
    private lateinit var amountText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var createdAtText: TextView

    private var uiState = EntryDetailsUiState()
    private var finishedForMissingEntry = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_entry_details)

        val contentRoot = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.toolbar_more).setOnClickListener { showActionsSheet() }
        findViewById<LinearLayout>(R.id.btn_edit_entry).setOnClickListener { showEditDialog() }

        accentStrip = findViewById(R.id.entry_accent_strip)
        typeChip = findViewById(R.id.entry_type_chip)
        dateTimeText = findViewById(R.id.entry_datetime)
        amountText = findViewById(R.id.entry_amount)
        descriptionText = findViewById(R.id.entry_description)
        createdAtText = findViewById(R.id.entry_created_at)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: EntryDetailsUiState) {
        uiState = state
        // The entry was deleted (elsewhere, or by this screen's own Delete action) — nothing left
        // to show; leave once, not on every subsequent empty emission.
        if (!state.exists) {
            if (!finishedForMissingEntry) {
                finishedForMissingEntry = true
                finish()
            }
            return
        }

        val accentColor = ContextCompat.getColor(this, if (state.isCashIn) R.color.success_green else R.color.danger_red)
        val accentBg = ContextCompat.getColor(this, if (state.isCashIn) R.color.success_green_bg else R.color.danger_red_bg)

        accentStrip.backgroundTintList = ColorStateList.valueOf(accentColor)
        typeChip.text = if (state.isCashIn) "CASH IN" else "CASH OUT"
        typeChip.setTextColor(accentColor)
        typeChip.backgroundTintList = ColorStateList.valueOf(accentBg)
        dateTimeText.text = state.entryDateTimeText
        amountText.text = "Rs ${state.amountText}"
        amountText.setTextColor(accentColor)
        descriptionText.text = state.description.ifBlank { "No description" }
        createdAtText.text = state.createdLabelText
    }

    // ─────────────────────────────────────────────────────────────────────
    // Actions bottom sheet: move / copy / delete
    // ─────────────────────────────────────────────────────────────────────
    private fun showActionsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_entry_actions, null)

        view.findViewById<ImageButton>(R.id.close_entry_actions).setOnClickListener { dialog.dismiss() }
        view.findViewById<LinearLayout>(R.id.action_move_entry).setOnClickListener {
            dialog.dismiss()
            promptMoveEntry()
        }
        view.findViewById<LinearLayout>(R.id.action_copy_entry).setOnClickListener {
            dialog.dismiss()
            promptCopyEntry()
        }
        view.findViewById<LinearLayout>(R.id.action_delete_entry).setOnClickListener {
            dialog.dismiss()
            confirmDeleteEntry()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun promptMoveEntry() {
        val targets = uiState.otherBooks
        if (targets.isEmpty()) {
            Toast.makeText(this, "Add another book first to move this entry into it", Toast.LENGTH_SHORT).show()
            return
        }
        PickTargetDialog.show(
            context = this,
            iconRes = R.drawable.ic_move,
            headerTitle = "Move entry to",
            headerSubtitle = "Entry leaves this book",
            items = targets.map { PickTargetDialog.Item(it.id, it.name, "${it.entryCount} entries") },
        ) { picked ->
            viewModel.onEvent(EntryDetailsUiEvent.Move(picked.id))
            Toast.makeText(this, "Moved to ${picked.title}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun promptCopyEntry() {
        val targets = uiState.otherBooks
        if (targets.isEmpty()) {
            Toast.makeText(this, "Add another book first to copy this entry into it", Toast.LENGTH_SHORT).show()
            return
        }
        PickTargetDialog.show(
            context = this,
            iconRes = R.drawable.ic_duplicate,
            headerTitle = "Copy entry to",
            headerSubtitle = "Entry stays in this book too",
            items = targets.map { PickTargetDialog.Item(it.id, it.name, "${it.entryCount} entries") },
        ) { picked ->
            viewModel.onEvent(EntryDetailsUiEvent.Copy(picked.id))
            Toast.makeText(this, "Copied to ${picked.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteEntry() {
        DeleteConfirmDialog.show(
            context = this,
            title = "Delete this entry?",
            subtitle = "You can undo right after deleting.",
        ) {
            viewModel.onEvent(EntryDetailsUiEvent.Delete)
            Toast.makeText(this, "Entry deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Edit — reuses the same bottom sheet as add-entry, prefilled, type fixed.
    // ─────────────────────────────────────────────────────────────────────
    private fun showEditDialog() {
        val state = uiState
        EntryFormDialog.show(
            context = this,
            isCashIn = state.isCashIn,
            editing = true,
            prefill = EntryFormDialog.Prefill(
                amountText = state.amount.format(),
                description = state.description,
                entryAtMillis = state.entryAtMillis,
            ),
        ) { amount, description, entryAt, _ ->
            viewModel.onEvent(EntryDetailsUiEvent.SaveEdit(amount = amount, description = description, entryAt = entryAt))
        }
    }
}
