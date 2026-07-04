package com.elegen.elegencashbook

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
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
import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.feature.book.BookDetailsUiEvent
import com.elegen.elegencashbook.feature.book.BookDetailsUiState
import com.elegen.elegencashbook.feature.book.BookDetailsViewModel
import com.elegen.elegencashbook.feature.book.EntryItem
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BookDetailsActivity : AppCompatActivity() {

    private val viewModel: BookDetailsViewModel by viewModels()

    private lateinit var tvNetBalance:      TextView
    private lateinit var tvEntriesInBook:   TextView
    private lateinit var tvTotalIn:         TextView
    private lateinit var tvTotalOut:        TextView
    private lateinit var tvEntryCount:      TextView
    private lateinit var entryListContainer: LinearLayout

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

        findViewById<TextView>(R.id.btn_view_reports).setOnClickListener {
            Toast.makeText(this, "View Reports", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton>(R.id.toolbar_more).setOnClickListener { showBookMenu(it) }

        // ── Bottom buttons ────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btn_cash_in).setOnClickListener  { showEntryDialog(true)  }
        findViewById<MaterialButton>(R.id.btn_cash_out).setOnClickListener { showEntryDialog(false) }

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
        val dialog = BottomSheetDialog(this)
        val view   = LayoutInflater.from(this).inflate(R.layout.dialog_add_entry, null)
        dialog.setContentView(view)

        val accentHex     = if (isCashIn) "#16A34A" else "#DC2626"
        val accentBgHex   = if (isCashIn) "#DCFCE7" else "#FEE2E2"
        val accentColor   = Color.parseColor(accentHex)
        val accentBgColor = Color.parseColor(accentBgHex)

        // Title
        view.findViewById<TextView>(R.id.dialog_title).apply {
            text = if (isCashIn) "Add Cash In Entry" else "Add Cash Out Entry"
            setTextColor(accentColor)
        }

        // Header icon circle
        view.findViewById<FrameLayout>(R.id.dialog_icon_bg).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentBgColor)
        }
        view.findViewById<ImageView>(R.id.dialog_icon).apply {
            setImageResource(if (isCashIn) R.drawable.ic_plus else R.drawable.ic_minus)
            imageTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }

        // Amount box stroke colour + text colour
        view.findViewById<TextInputLayout>(R.id.layout_amount).apply {
            boxStrokeColor = accentColor
            hintTextColor = android.content.res.ColorStateList.valueOf(accentColor)
        }
        view.findViewById<TextInputEditText>(R.id.et_amount).setTextColor(accentColor)

        // Save button colour
        view.findViewById<MaterialButton>(R.id.btn_save).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }

        // Date / time
        val cal     = Calendar.getInstance()
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("hh:mm a",   Locale.getDefault())
        val tvDate  = view.findViewById<TextView>(R.id.tv_selected_date)
        val tvTime  = view.findViewById<TextView>(R.id.tv_selected_time)
        tvDate.text = dateFmt.format(cal.time)
        tvTime.text = timeFmt.format(cal.time)

        view.findViewById<LinearLayout>(R.id.btn_pick_date).setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d); tvDate.text = dateFmt.format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        view.findViewById<LinearLayout>(R.id.btn_pick_time).setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                tvTime.text = timeFmt.format(cal.time)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }

        view.findViewById<ImageButton>(R.id.dialog_back_btn).setOnClickListener { dialog.dismiss() }

        val etAmount = view.findViewById<TextInputEditText>(R.id.et_amount)
        val etRemark = view.findViewById<TextInputEditText>(R.id.et_remark)
        val layoutAmount = view.findViewById<TextInputLayout>(R.id.layout_amount)

        // Field validation only; all persistence goes through the ViewModel.
        fun collectAndSend(): Boolean {
            val amountStr = etAmount.text?.toString()?.trim() ?: ""
            if (amountStr.isEmpty()) { layoutAmount.error = "Enter amount"; return false }
            val amount = Money.parse(amountStr) ?: run { layoutAmount.error = "Invalid amount"; return false }
            if (!amount.isPositive) { layoutAmount.error = "Amount must be greater than 0"; return false }
            layoutAmount.error = null
            viewModel.onEvent(
                BookDetailsUiEvent.SaveEntry(
                    amount = amount,
                    description = etRemark.text?.toString()?.trim() ?: "",
                    isCashIn = isCashIn,
                    entryAt = cal.timeInMillis,
                )
            )
            return true
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            if (collectAndSend()) dialog.dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_save_add_new).setOnClickListener {
            if (collectAndSend()) {
                dialog.dismiss()
                showEntryDialog(isCashIn)   // reopen fresh
            }
        }

        dialog.show()
        etAmount.requestFocus()
    }

    private fun confirmDelete(entry: EntryItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete entry?")
            .setMessage("You can undo right after deleting.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.onEvent(BookDetailsUiEvent.DeleteEntry(entry.id))
                Snackbar.make(entryListContainer, "Entry deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        viewModel.onEvent(BookDetailsUiEvent.RestoreEntry(entry.id))
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Book menu: rename / duplicate / add members / move / delete (same actions as Main's list)
    // ─────────────────────────────────────────────────────────────────────
    private fun bookName() = findViewById<TextView>(R.id.toolbar_title).text.toString()

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
        action(R.id.action_duplicate) {
            viewModel.onEvent(BookDetailsUiEvent.DuplicateBook)
            Toast.makeText(this, "\"${bookName()}\" duplicated", Toast.LENGTH_SHORT).show()
        }
        action(R.id.action_add_members) {
            Toast.makeText(this, "Sharing books with members is coming soon", Toast.LENGTH_SHORT).show()
        }
        action(R.id.action_move) { promptMoveBook() }
        action(R.id.action_delete) { confirmDeleteBook() }

        val xOffset = anchor.width - popupWidthPx
        popupWindow.showAsDropDown(anchor, xOffset, 4)
    }

    private fun promptRenameBook() {
        val input = TextInputEditText(this).apply {
            setText(bookName())
            setSelection(text?.length ?: 0)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename book")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    viewModel.onEvent(BookDetailsUiEvent.RenameBook(name))
                    findViewById<TextView>(R.id.toolbar_title).text = name
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteBook() {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${bookName()}\"?")
            .setMessage("This removes the book and its entries.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.onEvent(BookDetailsUiEvent.DeleteBook)
                Toast.makeText(this, "Book deleted", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMoveBook() {
        val targets = uiState.otherBusinesses
        if (targets.isEmpty()) {
            Toast.makeText(this, "Create another business first to move this book into it", Toast.LENGTH_SHORT).show()
            return
        }
        val names = targets.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Move \"${bookName()}\" to")
            .setItems(names) { _, index ->
                viewModel.onEvent(BookDetailsUiEvent.MoveBook(targets[index].id))
                Toast.makeText(this, "Moved to ${targets[index].name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun render(state: BookDetailsUiState) {
        uiState = state
        val count = state.entryCount
        tvNetBalance.text    = "Rs ${state.netText}"
        tvEntriesInBook.text = "$count ${if (count == 1) "entry" else "entries"} this book"
        tvTotalIn.text       = "Rs ${state.totalInText}"
        tvTotalOut.text      = "Rs ${state.totalOutText}"
        tvEntryCount.text    = "Showing $count ${if (count == 1) "entry" else "entries"}"
        refreshEntryList(state.entries)
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun refreshEntryList(entries: List<EntryItem>) {
        entryListContainer.removeAllViews()

        fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

        var lastDate: String? = null

        entries.forEach { entry ->

            // ── Date header (one per date group, entries are already date-ordered) ──
            if (entry.dateText != lastDate) {
                lastDate = entry.dateText
                entryListContainer.addView(TextView(this).apply {
                    text = entry.dateText
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#374151"))
                    setBackgroundColor(Color.parseColor("#F0F2F5"))
                    setPadding(px(16), px(4), px(16), px(4))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }

            // ── Divider ──
            entryListContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#E5E7EB"))
            })

            // ── Row container ──
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), px(12), px(16), px(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.WHITE)
                setOnLongClickListener { confirmDelete(entry); true }
            }

            val accentColor = if (entry.isCashIn) "#16A34A" else "#DC2626"
            val accentBg    = if (entry.isCashIn) "#DCFCE7" else "#FEE2E2"

            // Leading icon circle
            val iconCircle = FrameLayout(this).apply {
                background = ContextCompat.getDrawable(this@BookDetailsActivity, R.drawable.round_shape)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(accentBg))
                layoutParams = LinearLayout.LayoutParams(px(36), px(36))
            }
            iconCircle.addView(ImageView(this).apply {
                setImageResource(if (entry.isCashIn) R.drawable.ic_arrow_up_right else R.drawable.ic_arrow_down_left)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(accentColor))
                layoutParams = FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER)
            })

            // Middle: title + chip/meta
            val middleCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.marginStart = px(12)
                }
            }
            middleCol.addView(TextView(this).apply {
                text = entry.title
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#111827"))
            })

            val metaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = px(4) }
            }
            metaRow.addView(TextView(this).apply {
                text = "Cash"
                textSize = 11f
                setTextColor(Color.parseColor("#2563EB"))
                setBackgroundResource(R.drawable.bg_chip_blue)
                setPadding(px(8), px(2), px(8), px(2))
            })
            metaRow.addView(TextView(this).apply {
                text = "  Entry by You · ${entry.timeText}"
                textSize = 12f
                setTextColor(Color.parseColor("#6B7280"))
            })
            middleCol.addView(metaRow)

            // Trailing: amount + balance
            val amountCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            val sign = if (entry.isCashIn) "+" else "-"
            amountCol.addView(TextView(this).apply {
                text = "$sign Rs ${entry.amountText}"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(accentColor))
                gravity = Gravity.END
            })
            amountCol.addView(TextView(this).apply {
                text = "Bal Rs ${entry.runningBalanceText}"
                textSize = 12f
                setTextColor(Color.parseColor("#6B7280"))
                gravity = Gravity.END
            })

            row.addView(iconCircle)
            row.addView(middleCol)
            row.addView(amountCol)
            entryListContainer.addView(row)
        }
    }
}
