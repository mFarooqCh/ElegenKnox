package com.elegen.elegencashbook

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var tvEntriesInBook:   TextView
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
        tvEntriesInBook    = findViewById(R.id.tv_entries_in_book)
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
        val count = entries.size
        tvNetBalance.text    = "Rs ${net.toLong()}"
        tvEntriesInBook.text = "$count ${if (count == 1) "entry" else "entries"} this book"
        tvTotalIn.text       = "Rs ${totalIn.toLong()}"
        tvTotalOut.text      = "Rs ${totalOut.toLong()}"
        tvEntryCount.text = "Showing $count ${if (count == 1) "entry" else "entries"}"
        tvDateHeader.text = entries.firstOrNull()?.date ?: ""
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun refreshEntryList() {
        entryListContainer.removeAllViews()

        fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

        // entries[0] is newest; compute running balance in chronological order, oldest first.
        val runningBalances = DoubleArray(entries.size)
        var running = 0.0
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            running += if (entry.isCashIn) entry.amount else -entry.amount
            runningBalances[i] = running
        }

        entries.forEachIndexed { index, entry ->

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
                text = entry.remark.ifEmpty { if (entry.isCashIn) "Cash In" else "Cash Out" }
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
                text = "  Entry by You · ${entry.time}"
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
                text = "$sign Rs ${entry.amount.toLong()}"
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(accentColor))
                gravity = Gravity.END
            })
            amountCol.addView(TextView(this).apply {
                text = "Bal Rs ${runningBalances[index].toLong()}"
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