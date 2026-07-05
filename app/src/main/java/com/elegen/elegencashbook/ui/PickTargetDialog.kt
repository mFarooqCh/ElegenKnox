package com.elegen.elegencashbook.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.elegen.elegencashbook.R
import com.google.android.material.button.MaterialButton

/**
 * Radio-list "pick a target" popup shared by Move/Copy Entry and Move Book —
 * same card layout as the design mock, driven by a plain (id, title, subtitle) list.
 */
object PickTargetDialog {

    data class Item(val id: String, val title: String, val subtitle: String)

    fun show(
        context: Context,
        iconRes: Int,
        headerTitle: String,
        headerSubtitle: String,
        items: List<Item>,
        onConfirm: (Item) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pick_target, null)

        view.findViewById<ImageView>(R.id.target_header_icon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.target_header_title).text = headerTitle
        view.findViewById<TextView>(R.id.target_header_subtitle).text = headerSubtitle

        val listContainer = view.findViewById<LinearLayout>(R.id.target_list_container)
        val rows = items.map { item ->
            val row = LayoutInflater.from(context).inflate(R.layout.item_pick_target_row, listContainer, false)
            row.findViewById<ImageView>(R.id.row_icon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.row_title).text = item.title
            row.findViewById<TextView>(R.id.row_subtitle).text = item.subtitle
            listContainer.addView(row)
            row
        }

        var selectedIndex = 0

        fun renderSelection() {
            rows.forEachIndexed { index, row ->
                val selected = index == selectedIndex
                row.setBackgroundResource(
                    if (selected) R.drawable.bg_target_row_selected else R.drawable.bg_target_row_unselected
                )
                row.findViewById<ImageView>(R.id.row_radio).apply {
                    setImageResource(if (selected) R.drawable.ic_radio_checked else R.drawable.ic_radio_unchecked)
                    imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, if (selected) R.color.brand_blue else R.color.unselected_gray)
                    )
                }
            }
        }
        renderSelection()

        rows.forEachIndexed { index, row ->
            row.setOnClickListener {
                selectedIndex = index
                renderSelection()
            }
        }

        val dialog = AlertDialog.Builder(context).setView(view).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        view.findViewById<MaterialButton>(R.id.target_btn_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.target_btn_ok).setOnClickListener {
            dialog.dismiss()
            onConfirm(items[selectedIndex])
        }

        dialog.show()
    }
}
