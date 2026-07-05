package com.elegen.elegencashbook

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GenerateReportActivity : AppCompatActivity() {

    private var selectedReportRow: LinearLayout? = null
    private var selectedReportRadio: ImageView? = null
    private var selectedReportTitle: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContentView(R.layout.activity_generate_report)

        val rootView = findViewById<View>(R.id.generate_report_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.generate_report_back).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.filter_duration).setOnClickListener {
            pickOne(R.id.filter_duration_value, "Duration", listOf("All Time", "Today", "This Week", "This Month", "This Year"))
        }
        findViewById<LinearLayout>(R.id.filter_entry_type).setOnClickListener {
            pickOne(R.id.filter_entry_type_value, "Entry Type", listOf("All", "Cash In", "Cash Out"))
        }
        findViewById<LinearLayout>(R.id.filter_payment_mode).setOnClickListener {
            Toast.makeText(this, "Payment mode isn't tracked in this app yet", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.search_term_row).setOnClickListener { promptSearchTerm() }

        val reportRows = listOf(
            Triple(R.id.report_type_all_entries, R.id.report_type_all_entries_radio, R.id.report_type_all_entries_title),
            Triple(R.id.report_type_day_wise, R.id.report_type_day_wise_radio, R.id.report_type_day_wise_title),
            Triple(R.id.report_type_contact_wise, R.id.report_type_contact_wise_radio, R.id.report_type_contact_wise_title),
            Triple(R.id.report_type_category_wise, R.id.report_type_category_wise_radio, R.id.report_type_category_wise_title),
        )
        // "All Entries Report" is selected by default in the layout.
        selectedReportRow = findViewById(R.id.report_type_all_entries)
        selectedReportRadio = findViewById(R.id.report_type_all_entries_radio)
        selectedReportTitle = findViewById(R.id.report_type_all_entries_title)

        reportRows.forEach { (rowId, radioId, titleId) ->
            val row = findViewById<LinearLayout>(rowId)
            val radio = findViewById<ImageView>(radioId)
            val title = findViewById<TextView>(titleId)
            row.setOnClickListener { selectReportType(row, radio, title) }
        }

        findViewById<MaterialButton>(R.id.btn_generate_excel).setOnClickListener { generationComingSoon() }
        findViewById<MaterialButton>(R.id.btn_generate_pdf).setOnClickListener { generationComingSoon() }
    }

    private fun selectReportType(row: LinearLayout, radio: ImageView, title: TextView) {
        selectedReportRow?.setBackgroundResource(R.drawable.bg_target_row_unselected)
        selectedReportRadio?.setImageResource(R.drawable.ic_radio_unchecked)
        selectedReportRadio?.imageTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.unselected_gray))
        selectedReportTitle?.setTextColor(ContextCompat.getColor(this, R.color.text_dark))

        row.setBackgroundResource(R.drawable.bg_target_row_selected)
        radio.setImageResource(R.drawable.ic_radio_checked)
        radio.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_blue))
        title.setTextColor(ContextCompat.getColor(this, R.color.brand_blue))

        selectedReportRow = row; selectedReportRadio = radio; selectedReportTitle = title
    }

    private fun pickOne(valueViewId: Int, title: String, options: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, index ->
                findViewById<TextView>(valueViewId).text = options[index]
            }
            .show()
    }

    private fun promptSearchTerm() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(findViewById<TextView>(R.id.search_term_value).text.let { if (it == "None") "" else it })
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Search Term")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                findViewById<TextView>(R.id.search_term_value).text = text.ifEmpty { "None" }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generationComingSoon() {
        Toast.makeText(this, "Report generation coming soon", Toast.LENGTH_SHORT).show()
    }
}
