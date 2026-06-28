package com.elegen.elegencashbook

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.LayoutInflater
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
    private var totalIn  = 0.0
    private var totalOut = 0.0

    private lateinit var tvNetBalance:      TextView
    private lateinit var tvTotalIn:         TextView
    private lateinit var tvTotalOut:        TextView
    private lateinit var tvEntryCount:      TextView
    private lateinit var tvDateHeader:      TextView
    private lateinit var entryListContainer: LinearLayout

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
            intent.getStringExtra("book_name") ?: "June entries"
        findViewById<TextView>(R.id.toolbar_subtitle).text =
            intent.getStringExtra("book_meta") ?: "Add Member, Book Activity etc"

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }

        // ── Summary views ─────────────────────────────────────────────────
        tvNetBalance       = findViewById(R.id.tv_net_balance)
        tvTotalIn          = findViewById(R.id.tv_total_in)
        tvTotalOut         = findViewById(R.id.tv_total_out)
        tvEntryCount       = findViewById(R.id.tv_entry_count)
        tvDateHeader       = findViewById(R.id.tv_date_header)
        entryListContainer = findViewById(R.id.entry_list_container)

        findViewById<TextView>(R.id.btn_view_reports).setOnClickListener {
            Toast.makeText(this, "View Reports", Toast.LENGTH_SHORT).show()
        }

        // ── Bottom buttons ────────────────────────────────────────────────
        findViewById<MaterialButton>(R.id.btn_cash_in).setOnClickListener  { showEntryDialog(true)  }
        findViewById<MaterialButton>(R.id.btn_cash_out).setOnClickListener { showEntryDialog(false) }

        refreshSummary()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bottom-sheet dialog
    // ─────────────────────────────────────────────────────────────────────
    private fun showEntryDialog(isCashIn: Boolean) {
        val dialog = BottomSheetDialog(this)
        val view   = LayoutInflater.from(this).inflate(R.layout.dialog_add_entry, null)
        dialog.setContentView(view)

        val accentHex   = if (isCashIn) "#2563EB" else "#DC2626"
        val accentColor = Color.parseColor(accentHex)

        // Title
        view.findViewById<TextView>(R.id.dialog_title).apply {
            text = if (isCashIn) "Add Cash In Entry" else "Add Cash Out Entry"
            setTextColor(accentColor)
        }

        // Amount box stroke colour
        view.findViewById<TextInputLayout>(R.id.layout_amount).apply {
            boxStrokeColor = accentColor
        }

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

        fun collectAndSave(): Entry? {
            val amountStr = etAmount.text?.toString()?.trim() ?: ""
            if (amountStr.isEmpty()) { layoutAmount.error = "Enter amount"; return null }
            val amount = amountStr.toDoubleOrNull() ?: run { layoutAmount.error = "Invalid amount"; return null }
            layoutAmount.error = null
            return Entry(amount, etRemark.text?.toString()?.trim() ?: "", isCashIn,
                tvDate.text.toString(), tvTime.text.toString())
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            collectAndSave()?.let { entry -> saveEntry(entry); dialog.dismiss() }
        }

        view.findViewById<MaterialButton>(R.id.btn_save_add_new).setOnClickListener {
            collectAndSave()?.let { entry ->
                saveEntry(entry)
                dialog.dismiss()
                showEntryDialog(isCashIn)   // reopen fresh
            }
        }

        dialog.show()
        etAmount.requestFocus()
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun saveEntry(entry: Entry) {
        entries.add(0, entry)
        if (entry.isCashIn) totalIn += entry.amount else totalOut += entry.amount
        refreshSummary()
        refreshEntryList()
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun refreshSummary() {
        val net = totalIn - totalOut
        tvNetBalance.text = net.toLong().toString()
        tvTotalIn.text    = totalIn.toLong().toString()
        tvTotalOut.text   = totalOut.toLong().toString()
        val count = entries.size
        tvEntryCount.text = "Showing $count ${if (count == 1) "entry" else "entries"}"
        tvDateHeader.text = entries.firstOrNull()?.date ?: ""
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun refreshEntryList() {
        entryListContainer.removeAllViews()
        val dp = resources.displayMetrics.density

        fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

        entries.forEach { entry ->

            // ── Divider ──
            entryListContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#E5E7EB"))
            })

            // ── Row container ──
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(12), px(16), px(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.WHITE)
            }

            // Top: [Cash chip]  [amount / balance]
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
            }

            // "Cash" chip — fixed wrap_content, NOT weight=1
            val chipText = TextView(this).apply {
                text = "Cash"
                textSize = 12f
                setTextColor(Color.parseColor("#2563EB"))
                setBackgroundResource(R.drawable.bg_chip_blue)
                setPadding(px(10), px(3), px(10), px(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            // Spacer to push amount to right
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Amount + balance stacked right
            val amountCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.END
            }

            val amountColor = if (entry.isCashIn) "#16A34A" else "#DC2626"

            val tvAmount = TextView(this).apply {
                text = entry.amount.toLong().toString()
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(amountColor))
                gravity = Gravity.END
            }
            val tvBalance = TextView(this).apply {
                val running = totalIn - totalOut   // simplified; full running calc below
                text = "Balance: ${(totalIn - totalOut).toLong()}"
                textSize = 12f
                setTextColor(Color.parseColor("#6B7280"))
                gravity = Gravity.END
            }

            amountCol.addView(tvAmount)
            amountCol.addView(tvBalance)
            topRow.addView(chipText)
            topRow.addView(spacer)
            topRow.addView(amountCol)

            // Description
            val tvDesc = TextView(this).apply {
                text = entry.remark.ifEmpty { "—" }
                textSize = 15f
                setTextColor(Color.parseColor("#111827"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = px(6) }
            }

            // Footer: Entry by You  at HH:MM
            val footerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = px(4) }
            }
            footerRow.addView(TextView(this).apply {
                text = "Entry by You"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#16A34A"))
            })
            footerRow.addView(TextView(this).apply {
                text = "  at ${entry.time}"
                textSize = 12f
                setTextColor(Color.parseColor("#6B7280"))
            })

            row.addView(topRow)
            row.addView(tvDesc)
            row.addView(footerRow)
            entryListContainer.addView(row)
        }
    }
}