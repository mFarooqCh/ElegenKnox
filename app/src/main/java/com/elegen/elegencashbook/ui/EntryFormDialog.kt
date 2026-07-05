package com.elegen.elegencashbook.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.elegen.elegencashbook.R
import com.elegen.elegencashbook.core.money.Money
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Add/edit cash-in/out entry — same bottom sheet for both BookDetails (add) and EntryDetails (edit). */
object EntryFormDialog {

    data class Prefill(
        val amountText: String = "",
        val description: String = "",
        val entryAtMillis: Long = System.currentTimeMillis(),
    )

    fun show(
        context: Context,
        isCashIn: Boolean,
        editing: Boolean,
        prefill: Prefill = Prefill(),
        onSave: (amount: Money, description: String, entryAtMillis: Long, reopen: Boolean) -> Unit,
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_entry, null)
        dialog.setContentView(view)

        val accentColor = ContextCompat.getColor(context, if (isCashIn) R.color.success_green else R.color.danger_red)
        val accentBgColor = ContextCompat.getColor(context, if (isCashIn) R.color.success_green_bg else R.color.danger_red_bg)

        view.findViewById<TextView>(R.id.dialog_title).apply {
            text = "${if (editing) "Edit" else "Add"} ${if (isCashIn) "Cash In" else "Cash Out"} Entry"
            setTextColor(accentColor)
        }
        view.findViewById<FrameLayout>(R.id.dialog_icon_bg).backgroundTintList = ColorStateList.valueOf(accentBgColor)
        view.findViewById<ImageView>(R.id.dialog_icon).apply {
            setImageResource(if (isCashIn) R.drawable.ic_plus else R.drawable.ic_minus)
            imageTintList = ColorStateList.valueOf(accentColor)
        }
        view.findViewById<TextInputLayout>(R.id.layout_amount).apply {
            boxStrokeColor = accentColor
            hintTextColor = ColorStateList.valueOf(accentColor)
        }
        view.findViewById<TextInputEditText>(R.id.et_amount).setTextColor(accentColor)
        view.findViewById<MaterialButton>(R.id.btn_save).backgroundTintList = ColorStateList.valueOf(accentColor)
        // "Save & add new" only makes sense when creating, not when editing an existing entry.
        view.findViewById<MaterialButton>(R.id.btn_save_add_new).visibility = if (editing) View.GONE else View.VISIBLE

        val cal = Calendar.getInstance().apply { timeInMillis = prefill.entryAtMillis }
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val tvDate = view.findViewById<TextView>(R.id.tv_selected_date)
        val tvTime = view.findViewById<TextView>(R.id.tv_selected_time)
        tvDate.text = dateFmt.format(cal.time)
        tvTime.text = timeFmt.format(cal.time)

        view.findViewById<LinearLayout>(R.id.btn_pick_date).setOnClickListener {
            DatePickerDialog(context, { _, y, m, d ->
                cal.set(y, m, d); tvDate.text = dateFmt.format(cal.time)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        view.findViewById<LinearLayout>(R.id.btn_pick_time).setOnClickListener {
            TimePickerDialog(context, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
                tvTime.text = timeFmt.format(cal.time)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }

        view.findViewById<ImageButton>(R.id.dialog_back_btn).setOnClickListener { dialog.dismiss() }

        val etAmount = view.findViewById<TextInputEditText>(R.id.et_amount).apply { setText(prefill.amountText) }
        val etRemark = view.findViewById<TextInputEditText>(R.id.et_remark).apply { setText(prefill.description) }
        val layoutAmount = view.findViewById<TextInputLayout>(R.id.layout_amount)

        fun collectAndSend(reopen: Boolean): Boolean {
            val amountStr = etAmount.text?.toString()?.trim() ?: ""
            if (amountStr.isEmpty()) { layoutAmount.error = "Enter amount"; return false }
            val amount = Money.parse(amountStr) ?: run { layoutAmount.error = "Invalid amount"; return false }
            if (!amount.isPositive) { layoutAmount.error = "Amount must be greater than 0"; return false }
            layoutAmount.error = null
            onSave(amount, etRemark.text?.toString()?.trim() ?: "", cal.timeInMillis, reopen)
            return true
        }

        view.findViewById<MaterialButton>(R.id.btn_save).setOnClickListener {
            if (collectAndSend(reopen = false)) dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_save_add_new).setOnClickListener {
            if (collectAndSend(reopen = true)) dialog.dismiss()
        }

        dialog.show()
        etAmount.requestFocus()
    }
}
