package app.cablegram.phone

/** Selecting or deselecting one poster: all of its titles together. */
fun toggleSelection(selected: Set<String>, ids: List<String>): Set<String> =
    if (isSelected(selected, ids)) selected - ids.toSet() else selected + ids

fun isSelected(selected: Set<String>, ids: List<String>): Boolean = ids.isNotEmpty() && ids.all { it in selected }

/** What a removal touches, counted by where each title lives. Cloud copies are never part of it. */
data class RemovalSummary(val total: Int, val phoneItems: Int, val telegramItems: Int, val phoneBytes: Long)

/**
 * A Telegram title is hidden or deleted through the control plane; every other title is a phone file. Only a file kept inside
 * the app ([LibraryItem.copied]) counts towards the bytes "Also delete the files" frees.
 */
fun summarizeRemoval(items: List<LibraryItem>): RemovalSummary {
    val telegram = items.filter { it.sourceKind == "telegram" }
    val phone = items.filter { it.sourceKind != "telegram" }
    return RemovalSummary(items.size, phone.size, telegram.size, phone.filter { it.copied }.sumOf { it.fileSizeBytes ?: 0L })
}

/** Free up space only acts on titles with a verified copy elsewhere ([canRemoveLocalCopy]); the rest are listed as skipped. */
data class FreeUpSummary(val eligible: List<LibraryItem>, val skipped: List<LibraryItem>, val bytes: Long)

fun summarizeFreeUp(items: List<LibraryItem>): FreeUpSummary {
    val (eligible, skipped) = items.partition(::canRemoveLocalCopy)
    return FreeUpSummary(eligible, skipped, eligible.sumOf { it.fileSizeBytes ?: 0L })
}

enum class RemovalMode { RemoveFromLibrary, DeleteFiles }

/** Outcome of a bulk run: how many worked, and the titles to offer a Retry for. */
data class BulkOutcome(val done: Int, val failed: List<LibraryItem>)
