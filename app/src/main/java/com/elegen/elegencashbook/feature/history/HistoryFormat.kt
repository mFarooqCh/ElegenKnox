package com.elegen.elegencashbook.feature.history

import com.elegen.elegencashbook.core.money.Money
import com.elegen.elegencashbook.domain.model.HistoryAction
import com.elegen.elegencashbook.domain.model.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared by Book Activity and Entry History views — same trail, same presentation. */
data class HistoryItem(
    val action: HistoryAction,
    val actionLabel: String,
    val changesLines: List<String>,
    val timeText: String,
)

fun HistoryEntry.toHistoryItem(dateFmt: SimpleDateFormat) = HistoryItem(
    action = action,
    actionLabel = actionLabel(action),
    changesLines = changesLines(changes),
    timeText = dateFmt.format(Date(at)),
)

private fun actionLabel(action: HistoryAction) = when (action) {
    HistoryAction.CREATED -> "Created"
    HistoryAction.UPDATED -> "Edited"
    HistoryAction.RENAMED -> "Renamed"
    HistoryAction.MOVED -> "Moved"
    HistoryAction.COPIED -> "Copied"
    HistoryAction.DELETED -> "Deleted"
    HistoryAction.RESTORED -> "Restored"
    HistoryAction.CONFLICT_OVERWRITTEN -> "Overwritten by a newer edit"
}

private val changesDateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

private fun fieldLabel(field: String) = when (field) {
    "amountPaisa" -> "Amount"
    "entryDate" -> "Date"
    "bookId" -> "Book"
    "businessId" -> "Business"
    else -> field.replaceFirstChar { it.uppercase() }
}

private fun fieldValue(field: String, raw: String): String = when (field) {
    "amountPaisa" -> raw.toLongOrNull()?.let { "Rs ${Money(it).format()}" } ?: raw
    "entryDate" -> raw.toLongOrNull()?.let { changesDateFmt.format(Date(it)) } ?: raw
    "type" -> when (raw) {
        "CASH_IN" -> "Cash In"
        "CASH_OUT" -> "Cash Out"
        else -> raw
    }
    else -> raw
}

/** "field=old→new;..." → readable "Field: old → new" lines, with money/date/enum fields formatted for display. */
private fun changesLines(changes: String?): List<String> =
    changes?.split(";")?.mapNotNull { part ->
        val field = part.substringBefore('=', "")
        if (field.isEmpty()) return@mapNotNull null
        val diff = part.substringAfter('=', "")
        val (oldRaw, newRaw) = diff.split("→", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        "${fieldLabel(field)}: ${fieldValue(field, oldRaw)} → ${fieldValue(field, newRaw)}"
    } ?: emptyList()
