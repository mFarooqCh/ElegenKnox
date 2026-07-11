package com.elegen.elegencashbook.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.elegen.elegencashbook.R
import com.elegen.elegencashbook.domain.model.HistoryAction
import com.elegen.elegencashbook.feature.history.HistoryItem

/** Read-only edit-history trail popup — shared by Book Activity and Entry History. */
object HistoryDialog {

    private fun iconRes(action: HistoryAction) = when (action) {
        HistoryAction.CREATED -> R.drawable.ic_plus
        HistoryAction.UPDATED -> R.drawable.ic_edit
        HistoryAction.RENAMED -> R.drawable.ic_edit
        HistoryAction.MOVED -> R.drawable.ic_move
        HistoryAction.COPIED -> R.drawable.ic_duplicate
        HistoryAction.DELETED -> R.drawable.ic_delete
        HistoryAction.RESTORED -> R.drawable.ic_check_circle
        HistoryAction.CONFLICT_OVERWRITTEN -> R.drawable.ic_info
    }

    fun show(context: Context, title: String, items: List<HistoryItem>) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_history, null)

        view.findViewById<TextView>(R.id.history_header_title).text = title
        view.findViewById<TextView>(R.id.history_header_subtitle).text =
            if (items.isEmpty()) "No activity yet" else "${items.size} ${if (items.size == 1) "event" else "events"}"

        val listContainer = view.findViewById<LinearLayout>(R.id.history_list_container)
        view.findViewById<View>(R.id.history_scroll).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.history_empty_text).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        populate(context, listContainer, items)

        val dialog = AlertDialog.Builder(context).setView(view).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        view.findViewById<View>(R.id.history_close).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /** Fills [container] with one row per item plus hairline dividers — reused by the popup and by inline "History" sections. */
    fun populate(context: Context, container: LinearLayout, items: List<HistoryItem>) {
        container.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_history_row, container, false)
            row.findViewById<ImageView>(R.id.history_row_icon).setImageResource(iconRes(item.action))
            row.findViewById<TextView>(R.id.history_row_title).text = item.actionLabel
            row.findViewById<TextView>(R.id.history_row_time).text = item.timeText
            val changesView = row.findViewById<TextView>(R.id.history_row_changes)
            if (item.changesLines.isEmpty()) {
                changesView.visibility = View.GONE
            } else {
                changesView.text = item.changesLines.joinToString("\n")
            }
            container.addView(row)
            if (index < items.lastIndex) {
                val divider = View(context)
                divider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                divider.setBackgroundColor(context.getColor(R.color.divider_light))
                container.addView(divider)
            }
        }
    }
}
