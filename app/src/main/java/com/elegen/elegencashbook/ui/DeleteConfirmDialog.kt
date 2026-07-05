package com.elegen.elegencashbook.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.elegen.elegencashbook.R
import com.google.android.material.button.MaterialButton

/**
 * Shared "Delete this?" confirmation card — icon + title + warning line + optional
 * two-stat row (e.g. "1 Book" / "42 Entries") + Cancel/Delete buttons.
 */
object DeleteConfirmDialog {

    data class Stat(val value: String, val label: String)

    fun show(
        context: Context,
        title: String,
        subtitle: String,
        stat1: Stat? = null,
        stat2: Stat? = null,
        onConfirm: () -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_delete_confirm, null)

        view.findViewById<TextView>(R.id.delete_dialog_title).text = title
        view.findViewById<TextView>(R.id.delete_dialog_subtitle).text = subtitle

        if (stat1 != null && stat2 != null) {
            view.findViewById<TextView>(R.id.delete_stat1_value).text = stat1.value
            view.findViewById<TextView>(R.id.delete_stat1_label).text = stat1.label
            view.findViewById<TextView>(R.id.delete_stat2_value).text = stat2.value
            view.findViewById<TextView>(R.id.delete_stat2_label).text = stat2.label
        } else {
            view.findViewById<android.view.View>(R.id.delete_stats_row).visibility = android.view.View.GONE
        }

        val dialog = AlertDialog.Builder(context).setView(view).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        view.findViewById<MaterialButton>(R.id.delete_dialog_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.delete_dialog_confirm).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
    }
}
