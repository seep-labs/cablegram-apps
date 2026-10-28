package app.cablegram.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Phone only (spec 008). Everything here stays on the phone, encrypted, and is never sent anywhere.

/** Where the encrypted document lives. The phone uses the Keystore; tests use memory. */
interface DiscoveryStorage {
    fun read(): String?
    fun write(json: String)
    fun clear()
}

@Serializable data class StoredChoice(val chatId: Long, val kind: ChatKind, val title: String, val chosenAt: Long)

@Serializable data class StoredScanRow(
    val chatId: Long,
    val kind: ChatKind,
    val title: String,
    val protectedContent: Boolean,
    val videos: Int,
    val files: Int,
    val scannedAt: Long,
)

@Serializable data class DiscoveryState(
    /** Starts empty: nothing is chosen until the owner ticks it (FR-008). */
    val choice: List<StoredChoice> = emptyList(),
    val scan: List<StoredScanRow> = emptyList(),
    /** Chats already counted by an unfinished scan, so Look again can continue (US1 acceptance 5). */
    val scanned: List<Long> = emptyList(),
    val scanCompleted: Boolean = false,
    val includePersonalChats: Boolean = false,
    val includeSavedMessages: Boolean = false,
    /** Videos shorter than this are left out of the scan and the picker; 0 means any length. */
    val minVideoSeconds: Int = DEFAULT_MIN_VIDEO_SECONDS,
    /** TDLib file ids of thumbnails and photos downloaded for a chat, so unticking can delete exactly those. */
    val thumbnails: Map<Long, List<Int>> = emptyMap(),
)

class DiscoveryStore(private val storage: DiscoveryStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: DiscoveryState = load()

    private fun load(): DiscoveryState = runCatching { storage.read()?.let { json.decodeFromString<DiscoveryState>(it) } }.getOrNull() ?: DiscoveryState()

    @Synchronized private fun update(change: (DiscoveryState) -> DiscoveryState) {
        state = change(state)
        storage.write(json.encodeToString(state))
    }

    @Synchronized fun snapshot(): DiscoveryState = state
    fun choice(): Set<Long> = snapshot().choice.map { it.chatId }.toSet()
    fun switches() = snapshot().let { DiscoverySwitches(it.includePersonalChats, it.includeSavedMessages) }

    fun minVideoSeconds(): Int = snapshot().minVideoSeconds

    /** The counts depend on this length, so a change clears the scan results (the chosen chats stay). */
    fun setMinVideoSeconds(seconds: Int) = update {
        it.copy(minVideoSeconds = seconds, scan = emptyList(), scanned = emptyList(), scanCompleted = false)
    }

    fun setChoice(chosen: List<StoredChoice>) = update { it.copy(choice = chosen) }
    fun setSwitches(switches: DiscoverySwitches) = update {
        it.copy(includePersonalChats = switches.includePersonalChats, includeSavedMessages = switches.includeSavedMessages)
    }

    fun addScanRow(row: StoredScanRow) = update {
        it.copy(scan = it.scan.filter { r -> r.chatId != row.chatId } + row, scanned = (it.scanned + row.chatId).distinct())
    }
    fun finishScan(completed: Boolean) = update { it.copy(scanCompleted = completed) }
    fun restartScan() = update { it.copy(scanned = emptyList(), scanCompleted = false) }

    fun recordThumbnail(chatId: Long, fileId: Int) = update {
        it.copy(thumbnails = it.thumbnails + (chatId to ((it.thumbnails[chatId].orEmpty() + fileId).distinct())))
    }
    fun thumbnailsOf(chatId: Long): List<Int> = snapshot().thumbnails[chatId].orEmpty()

    /** Forgets a chat everywhere: choice, scan row and thumbnail ledger. The files are deleted by [DiscoveryCleaner]. */
    fun dropChat(chatId: Long) = update {
        it.copy(
            choice = it.choice.filter { c -> c.chatId != chatId },
            scan = it.scan.filter { r -> r.chatId != chatId },
            scanned = it.scanned - chatId,
            thumbnails = it.thumbnails - chatId,
        )
    }

    /** Disconnect Telegram: the choice, the switches, the counts and the ledger all go (spec edge cases). */
    fun clear() {
        state = DiscoveryState()
        storage.clear()
    }
}
