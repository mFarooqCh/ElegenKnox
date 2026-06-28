package com.elegen.elegencashbook

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.*

data class Entry(
    val amount: Double,
    val remark: String,
    val isCashIn: Boolean,
    val date: String,
    val time: String
)

class BookDetailsActivity : AppCompatActivity() {

    private val entries = mutableListOf<Entry>()
    private var totalIn = 0.0
    private var totalOut = 0.0

    private lateinit var tvNetBalance: TextView
    private lateinit var tvTotalIn: TextView
    private lateinit var tvTotalOut: TextView
    private lateinit var tvEntryCount: TextView
    private lateinit var tvDateHeader: TextView
    private lateinit var entryListContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_details)

        // ── Toolbar ──────────────────────────────────────────────────────
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.details_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.toolbar_title).text =
            intent.getStringExtra("book_name") ?: "June entries"
        findViewById<TextView>(R.id.toolbar_subtitle).text =
            intent.getStringExtra("book_meta") ?: "Add Member, Book Activity etc"

        findViewById<ImageButton>(R.id.btn_add_member).setOnClickListener {
            Toast.makeText(this, "Add Member", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btn_pdf).setOnClickListener {
            Toast.makeText(this, "Export PDF", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.btn_overflow).setOnClickListener {
            Toast.makeText(this, "More options", Toast.LENGTH_SHORT).show()
        }

        // ── Balance views ─────────────────────────────────────────────────
        tvNetBalance      = findViewById(R.id.tv_net_balance)
        tvTotalIn         = findViewById(R.id.tv_total_in)
        tvTotalOut        = findViewById(R.id.tv_total_out)
        tvEntryCount      = findViewById(R.id.tv_entry_count)
        tvDateHeader      = findViewById(R.id.tv_date_header)
        entryListContainer = findViewById(R.id.entry_list_container)

        findViewById<TextView>(R.id.btn_view_reports).setOnClickListener {
            Toast.makeText(this, "View Reports", Toast.LENGTH_SHORT).show()
        }

        // ── Bottom buttons ────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btn_cash_in).setOnClickListener {
            showEntryDialog(isCashIn = true)
        }
        findViewById<MaterialButton>(R.id.btn_cash_out).setOnClickListener {
            showEntryDialog(isCashIn = false)
        }

        refreshSummary()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bottom-sheet dialog for adding an entry
    // ─────────────────────────────────────────────────────────────────────
    private fun showEntryDialog(isCashIn: Boolean, clearAndReopen: Boolean = false) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_entry, null)
        dialog.setContentView(view)

        // ── Title colour: green for Cash In, red for Cash Out ──
        val accentColor = if (isCashIn) "#2563EB" else "#DC2626"
        view.findViewById<TextView>(R.id.dialog_title).apply {
            text = if (isCashIn) "Add Cash In Entry" else "Add Cash Out Entry"
            setTextColor(android.graphics.Color.parseColor(accentColor))
        }

        // ── Amount field stroke colour ──
        view.findViewById<TextInputLayout>(R.id.layout_amount).apply {
            boxStrokeColor = android.graphics.Color.parseColor(accentColor)
        }

        // ── Save button colour ──
        view.findViewById<MaterialButton>(R.id.btn_save).apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(accentColor)
            )
        }

        // ── Date / Time state ──
        val cal = Calendar.getInstance()
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

        val tvDate = view.findViewById<TextView>(R.id.tv_selected_date)
        val tvTime = view.findViewById<TextView>(R.id.tv_selected_time)
        tvDate.text = dateFmt.format(cal.time)
        tvTime.text = timeFmt.format(cal.time)

        view.findViewById<LinearLayout>(R.id.btn_pick_date).setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d)
                tvDate.text = dateFmt.format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        view.findViewById<LinearLayout>(R.id.btn_pick_time).setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                tvTime.text = timeFmt.format(cal.time)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }

        // ── Back / close ──
        view.findViewById<ImageButton>(R.id.dialog_back_btn).setOnClickListener {
            dialog.dismiss()
        }

        val etAmount = view.findViewById<TextInputEditText>(R.id.et_amount)
        val etRemark = view.findViewById<TextInputEditText>(R.id.et_remark)

        // ── SAVE ──
        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            val amountStr = etAmount.text?.toString()?.trim() ?: ""
            if (amountStr.isEmpty()) {
                view.findViewById<TextInputLayout>(R.id.layout_amount).error = "Enter amount"
                return@setOnClickListener
            }
            val amount = amountStr.toDoubleOrNull() ?: run {
                view.findViewById<TextInputLayout>(R.id.layout_amount).error = "Invalid amount"
                return@setOnClickListener
            }
            val remark = etRemark.text?.toString()?.trim() ?: ""
            saveEntry(Entry(amount, remark, isCashIn, tvDate.text.toString(), tvTime.text.toString()))
            dialog.dismiss()
        }

        // ── SAVE & ADD NEW ──
        view.findViewById<MaterialButton>(R.id.btn_save_add_new).setOnClickListener {
            val amountStr = etAmount.text?.toString()?.trim() ?: ""
            if (amountStr.isEmpty()) {
                view.findViewById<TextInputLayout>(R.id.layout_amount).error = "Enter amount"
                return@setOnClickListener
            }
            val amount = amountStr.toDoubleOrNull() ?: run {
                view.findViewById<TextInputLayout>(R.id.layout_amount).error = "Invalid amount"
                return@setOnClickListener
            }
            val remark = etRemark.text?.toString()?.trim() ?: ""
            saveEntry(Entry(amount, remark, isCashIn, tvDate.text.toString(), tvTime.text.toString()))
            dialog.dismiss()
            // reopen fresh dialog of same type
            showEntryDialog(isCashIn)
        }

        if (clearAndReopen) etAmount.text?.clear()
        dialog.show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Save entry and refresh UI
    // ─────────────────────────────────────────────────────────────────────
    private fun saveEntry(entry: Entry) {
        entries.add(0, entry) // newest first
        if (entry.isCashIn) totalIn += entry.amount else totalOut += entry.amount
        refreshSummary()
        refreshEntryList()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Update balance summary card
    // ─────────────────────────────────────────────────────────────────────
    private fun refreshSummary() {
        val net = totalIn - totalOut
        tvNetBalance.text = net.toLong().toString()
        tvTotalIn.text    = totalIn.toLong().toString()
        tvTotalOut.text   = totalOut.toLong().toString()
        tvEntryCount.text = "Showing ${entries.size} ${if (entries.size == 1) "entry" else "entries"}"
        if (entries.isNotEmpty()) {
            tvDateHeader.text = entries.first().date
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Rebuild entry rows dynamically
    // ─────────────────────────────────────────────────────────────────────
    private fun refreshEntryList() {
        entryListContainer.removeAllViews()
        var runningBalance = totalIn - totalOut

        entries.forEach { entry ->
            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(android.graphics.Color.parseColor("#E5E7EB"))
            }

            // Row layout
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(android.graphics.Color.WHITE)
            }

            // Top: Cash chip + amount col
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // "Cash" chip
            val chipText = TextView(this).apply {
                text = "Cash"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#2563EB"))
                setBackgroundResource(R.drawable.bg_chip_blue)
                setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.weight = 1f; it.width = 0 }
            }

            // Amount + balance col
            val amountCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }

            val amountColor = if (entry.isCashIn) "#16A34A" else "#DC2626"
            val amountSign  = if (entry.isCashIn) "" else ""

            val tvAmount = TextView(this).apply {
                text = "${amountSign}${entry.amount.toLong()}"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor(amountColor))
                gravity = android.view.Gravity.END
            }

            val tvBalance = TextView(this).apply {
                text = "Balance: ${runningBalance.toLong()}"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#6B7280"))
                gravity = android.view.Gravity.END
            }

            // Update running balance for next row
            runningBalance -= if (entry.isCashIn) entry.amount else -entry.amount

            amountCol.addView(tvAmount)
            amountCol.addView(tvBalance)
            topRow.addView(chipText)
            topRow.addView(amountCol)

            // Description
            val tvDesc = TextView(this).apply {
                text = entry.remark.ifEmpty { "—" }
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#111827"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(6) }
            }

            // Footer: Entry by You + time
            val footerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(4) }
            }

            val tvBy = TextView(this).apply {
                text = "Entry by You"
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#16A34A"))
            }

            val tvTime = TextView(this).apply {
                text = "  at ${entry.time}"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#6B7280"))
            }

            footerRow.addView(tvBy)
            footerRow.addView(tvTime)

            row.addView(topRow)
            row.addView(tvDesc)
            row.addView(footerRow)

            entryListContainer.addView(divider)
            entryListContainer.addView(row)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}