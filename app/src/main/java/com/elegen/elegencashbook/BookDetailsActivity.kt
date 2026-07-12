package com.elegen.elegencashbook

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.elegen.elegencashbook.core.permission.Permission
import com.elegen.elegencashbook.core.ui.setPrimaryEnabled
import com.elegen.elegencashbook.feature.book.BookDetailsUiEvent
import com.elegen.elegencashbook.feature.book.BookDetailsUiState
import com.elegen.elegencashbook.feature.book.BookDetailsViewModel
import com.elegen.elegencashbook.feature.book.EntryItem
import com.elegen.elegencashbook.ui.DeleteConfirmDialog
import com.elegen.elegencashbook.ui.EntryFormDialog
import com.elegen.elegencashbook.ui.HistoryDialog
import com.elegen.elegencashbook.ui.PickTargetDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BookDetailsActivity : AppCompatActivity() {

    private val viewModel: BookDetailsViewModel by viewModels()

    private lateinit var tvNetBalance:      TextView
    private lateinit var tvEntriesInBook:   TextView
    private lateinit var tvTotalIn:         TextView
    private lateinit var tvTotalOut:        TextView
    private lateinit var tvEntryCount:      TextView
    private lateinit var entryListContainer: RecyclerView
    private lateinit var btnCashIn: MaterialButton
    private lateinit var btnCashOut: MaterialButton
    private lateinit var toolbarAddMember: ImageButton
    private lateinit var entriesRefresh: SwipeRefreshLayout
    private val entryAdapter = EntryAdapter()

    private var uiState = BookDetailsUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_book_details)

        val contentRoot = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // ── Toolbar ──────────────────────────────────────────────────────
        findViewById<TextView>(R.id.toolbar_title).text =
            intent.getStringExtra("book_name") ?: "Book"
        findViewById<TextView>(R.id.toolbar_subtitle).text =
            intent.getStringExtra("book_meta") ?: "Add Member, Book Activity etc"

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }

        // ── Summary views ─────────────────────────────────────────────────
        tvNetBalance       = findViewById(R.id.tv_net_balance)
        tvEntriesInBook    = findViewById(R.id.tv_entries_in_book)
        tvTotalIn          = findViewById(R.id.tv_total_in)
        tvTotalOut         = findViewById(R.id.tv_total_out)
        tvEntryCount       = findViewById(R.id.tv_entry_count)
        entryListContainer = findViewById(R.id.entry_list_container)
        entryListContainer.layoutManager = LinearLayoutManager(this)
        entryListContainer.adapter = entryAdapter

        // Swipe down to force an immediate pull instead of waiting on Realtime/the periodic
        // worker — real bug found on-device: a shared viewer's book stayed stale until the app
        // was fully closed and reopened.
        entriesRefresh = findViewById(R.id.entries_refresh)
        entriesRefresh.setColorSchemeResources(R.color.brand)
        entriesRefresh.setOnRefreshListener { viewModel.onEvent(BookDetailsUiEvent.Refresh) }

        findViewById<TextView>(R.id.btn_view_reports).setOnClickListener {
            startActivity(Intent(this, GenerateReportActivity::class.java))
        }

        findViewById<ImageButton>(R.id.toolbar_more).setOnClickListener { showBookMenu(it) }
        toolbarAddMember = findViewById(R.id.toolbar_add_member)
        toolbarAddMember.setOnClickListener { openBookAccess() }

        // ── Bottom buttons ────────────────────────────────────────────────
        btnCashIn = findViewById(R.id.btn_cash_in)
        btnCashOut = findViewById(R.id.btn_cash_out)
        btnCashIn.setOnClickListener  { showEntryDialog(true)  }
        btnCashOut.setOnClickListener { showEntryDialog(false) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val rect = android.graphics.Rect()
                focused.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bottom-sheet dialog
    // ─────────────────────────────────────────────────────────────────────
    private fun showEntryDialog(isCashIn: Boolean) {
        EntryFormDialog.show(context = this, isCashIn = isCashIn, editing = false) { amount, description, entryAt, reopen ->
            viewModel.onEvent(
                BookDetailsUiEvent.SaveEntry(
                    amount = amount,
                    description = description,
                    isCashIn = isCashIn,
                    entryAt = entryAt,
                )
            )
            if (reopen) showEntryDialog(isCashIn)
        }
    }

    private fun confirmDelete(entry: EntryItem) {
        DeleteConfirmDialog.show(
            context = this,
            title = "Delete this entry?",
            subtitle = "You can undo right after deleting.",
        ) {
            viewModel.onEvent(BookDetailsUiEvent.DeleteEntry(entry.id))
            Snackbar.make(entryListContainer, "Entry deleted", Snackbar.LENGTH_LONG)
                .setAction("Undo") {
                    viewModel.onEvent(BookDetailsUiEvent.RestoreEntry(entry.id))
                }
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Book menu: rename / duplicate / add members / move / delete (same actions as Main's list)
    // ─────────────────────────────────────────────────────────────────────
    private fun bookName() = findViewById<TextView>(R.id.toolbar_title).text.toString()

    private fun openBookAccess() {
        startActivity(
            Intent(this, BookAccessActivity::class.java)
                .putExtra("book_id", intent.getStringExtra("book_id"))
                .putExtra("book_name", bookName())
                .putExtra("business_id", intent.getStringExtra("business_id"))
        )
    }

    private fun showBookMenu(anchor: View) {
        val popupWidthPx = (180 * resources.displayMetrics.density).toInt()
        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_book_menu, null)
        val popupWindow = PopupWindow(popupView, popupWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        fun action(id: Int, block: () -> Unit) {
            popupView.findViewById<View>(id).setOnClickListener {
                popupWindow.dismiss()
                block()
            }
        }

        action(R.id.action_rename) { promptRenameBook() }
        action(R.id.action_duplicate) { viewModel.onEvent(BookDetailsUiEvent.DuplicateBook) }
        action(R.id.action_add_members) { openBookAccess() }
        action(R.id.action_move) { promptMoveBook() }
        action(R.id.action_history) { HistoryDialog.show(this, "\"${bookName()}\" Activity", uiState.historyItems) }
        action(R.id.action_delete) { confirmDeleteBook() }

        val perms = uiState.permissions
        fun setVisible(id: Int, visible: Boolean) {
            popupView.findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
        }
        setVisible(R.id.action_rename, Permission.BOOK_EDIT in perms)
        setVisible(R.id.action_duplicate, Permission.BOOK_ADD in perms)
        setVisible(R.id.action_add_members, Permission.MEMBER_MANAGE in perms)
        setVisible(R.id.action_move, Permission.BOOK_EDIT in perms)
        setVisible(R.id.action_delete, Permission.BOOK_DELETE in perms)

        val xOffset = anchor.width - popupWidthPx
        popupWindow.showAsDropDown(anchor, xOffset, 4)
    }

    private fun promptRenameBook() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_book, null)
        val title = view.findViewById<TextView>(R.id.book_sheet_title)
        val bookNameInput = view.findViewById<TextInputEditText>(R.id.book_name_input)
        val renameButton = view.findViewById<MaterialButton>(R.id.add_book_button)
        val bookNameLayout = view.findViewById<TextInputLayout>(R.id.book_name_layout)
        val closeButton = view.findViewById<ImageButton>(R.id.close_book_sheet)
        view.findViewById<View>(R.id.book_suggestions_label).visibility = View.GONE
        view.findViewById<View>(R.id.book_suggestions).visibility = View.GONE

        title.text = "Rename Book"
        renameButton.text = "RENAME"
        bookNameInput.setText(bookName())
        bookNameInput.setSelection(bookNameInput.text?.length ?: 0)
        renameButton.setPrimaryEnabled(true)
        bookNameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                renameButton.setPrimaryEnabled(hasText)
                if (hasText) bookNameLayout.error = null
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        closeButton.setOnClickListener { dialog.dismiss() }
        renameButton.setOnClickListener {
            val name = bookNameInput.text?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) {
                viewModel.onEvent(BookDetailsUiEvent.RenameBook(name))
                findViewById<TextView>(R.id.toolbar_title).text = name
                dialog.dismiss()
            } else {
                bookNameLayout.error = "Name is required"
                bookNameLayout.boxStrokeColor = ContextCompat.getColor(this, R.color.danger_red)
                bookNameInput.requestFocus()
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun confirmDeleteBook() {
        DeleteConfirmDialog.show(
            context = this,
            title = "Delete this book?",
            subtitle = "All entries inside will be permanently deleted. This can't be undone.",
            stat1 = DeleteConfirmDialog.Stat("1", "Book"),
            stat2 = DeleteConfirmDialog.Stat("${uiState.entryCount}", "Entries"),
        ) {
            viewModel.onEvent(BookDetailsUiEvent.DeleteBook)
            Toast.makeText(this, "Book deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun promptMoveBook() {
        val targets = uiState.otherBusinesses
        if (targets.isEmpty()) {
            Toast.makeText(this, "Create another business first to move this book into it", Toast.LENGTH_SHORT).show()
            return
        }
        PickTargetDialog.show(
            context = this,
            iconRes = R.drawable.ic_move,
            headerTitle = "Move \"${bookName()}\" to",
            headerSubtitle = "Book leaves its current business",
            items = targets.map { PickTargetDialog.Item(it.id, it.name, "${it.bookCount} books") },
        ) { picked ->
            viewModel.onEvent(BookDetailsUiEvent.MoveBook(picked.id))
            Toast.makeText(this, "Moved to ${picked.title}", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun render(state: BookDetailsUiState) {
        uiState = state
        val count = state.entryCount
        tvNetBalance.text    = "Rs ${state.netText}"
        tvNetBalance.setTextColor(
            ContextCompat.getColor(this, if (state.netIsNegative) R.color.danger_red else R.color.text_dark)
        )
        tvEntriesInBook.text = "$count ${if (count == 1) "entry" else "entries"} this book"
        tvTotalIn.text       = "Rs ${state.totalInText}"
        tvTotalOut.text      = "Rs ${state.totalOutText}"
        tvEntryCount.text    = "Showing $count ${if (count == 1) "entry" else "entries"}"
        entryAdapter.submit(state.entries)

        val perms = state.permissions
        btnCashIn.isEnabled = Permission.TX_ADD in perms
        btnCashOut.isEnabled = Permission.TX_ADD in perms
        toolbarAddMember.visibility = if (Permission.MEMBER_MANAGE in perms) View.VISIBLE else View.GONE
        entriesRefresh.isRefreshing = state.isRefreshing

        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.onEvent(BookDetailsUiEvent.ErrorShown)
        }
    }

    /** [entries] arrive pre-grouped by date; a header row is inserted whenever the date changes. */
    private sealed interface EntryRow {
        data class Header(val date: String) : EntryRow
        data class Line(val entry: EntryItem) : EntryRow
    }

    private inner class EntryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val rows = mutableListOf<EntryRow>()

        fun submit(entries: List<EntryItem>) {
            rows.clear()
            var lastDate: String? = null
            entries.forEach { entry ->
                if (entry.dateText != lastDate) {
                    lastDate = entry.dateText
                    rows += EntryRow.Header(entry.dateText)
                }
                rows += EntryRow.Line(entry)
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is EntryRow.Header -> VIEW_TYPE_HEADER
            is EntryRow.Line -> VIEW_TYPE_LINE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.item_entry_date_header, parent, false))
            } else {
                EntryViewHolder(inflater.inflate(R.layout.item_entry_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is EntryRow.Header -> (holder as HeaderViewHolder).bind(row.date)
                is EntryRow.Line -> (holder as EntryViewHolder).bind(row.entry)
            }
        }

        override fun getItemCount() = rows.size

        private inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val text: TextView = view.findViewById(R.id.entry_header_text)
            fun bind(date: String) {
                text.text = date
            }
        }

        private inner class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val iconBg: FrameLayout = view.findViewById(R.id.entry_icon_bg)
            private val icon: ImageView = view.findViewById(R.id.entry_icon)
            private val title: TextView = view.findViewById(R.id.entry_title)
            private val meta: TextView = view.findViewById(R.id.entry_meta)
            private val amount: TextView = view.findViewById(R.id.entry_amount)
            private val balance: TextView = view.findViewById(R.id.entry_balance)

            fun bind(entry: EntryItem) {
                val accentColor = ContextCompat.getColor(itemView.context, if (entry.isCashIn) R.color.success_green else R.color.danger_red)
                val accentBg = ContextCompat.getColor(itemView.context, if (entry.isCashIn) R.color.success_green_bg else R.color.danger_red_bg)

                iconBg.backgroundTintList = ColorStateList.valueOf(accentBg)
                icon.setImageResource(if (entry.isCashIn) R.drawable.ic_arrow_up_right else R.drawable.ic_arrow_down_left)
                icon.imageTintList = ColorStateList.valueOf(accentColor)

                title.text = entry.title
                meta.text = "  Entry by You · ${entry.timeText}"

                val sign = if (entry.isCashIn) "+" else "-"
                amount.text = "$sign Rs ${entry.amountText}"
                amount.setTextColor(accentColor)
                balance.text = "Bal Rs ${entry.runningBalanceText}"

                itemView.setOnClickListener {
                    startActivity(
                        Intent(itemView.context, EntryDetailsActivity::class.java).apply {
                            putExtra("entry_id", entry.id)
                            putExtra("business_id", intent.getStringExtra("business_id"))
                        }
                    )
                }
                itemView.setOnLongClickListener { confirmDelete(entry); true }
            }
        }

        private val VIEW_TYPE_HEADER = 0
        private val VIEW_TYPE_LINE = 1
    }
}
