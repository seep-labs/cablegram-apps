package app.cablegram.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Switches from Telegram → Advanced settings (US5). Both are off until the owner turns them on. */
data class DiscoverySwitches(val includePersonalChats: Boolean = false, val includeSavedMessages: Boolean = false)

/**
 * Phone-only video discovery (spec 008). Every rule that keeps the promise lives here, not in the screens:
 * a live [ScanPermit] for any scan, only chosen chats for any read, and a throttle for every request.
 */
class TelegramDiscovery(
    private val transport: DiscoveryTransport,
    private val gate: DiscoveryGate,
    private val throttle: RequestThrottle,
    private val libraryChatId: () -> Long?,
) {
    /** Channels and groups (FR-005), plus personal chats and Saved Messages only if their switch is on. */
    fun discoveryChats(permit: ScanPermit, switches: DiscoverySwitches): Flow<DiscoveryChat> = flow {
        gate.requirePermit(permit)
        val me = throttle.run { transport.myUserId() }
        val ids = throttle.run { transport.chatIds() }
        for (id in ids) {
            gate.requirePermit(permit)
            if (id == libraryChatId()) continue
            val chat = throttle.run { transport.chat(id) } ?: continue
            val kind = classify(chat, me, switches) ?: continue
            permit.listed += id
            emit(DiscoveryChat(DiscoveryChatId(id), kind, chat.title, chat.protectedContent))
        }
    }

    /** Videos plus files, counted separately; files make the total "about" (FR-006). */
    suspend fun chatVideoCount(permit: ScanPermit, chatId: DiscoveryChatId): VideoCount {
        gate.requirePermit(permit)
        if (chatId.raw !in permit.listed) throw DiscoveryRefused("Chat was not listed by this scan")
        val videos = throttle.run { transport.messageCount(chatId.raw, files = false) }
        val files = throttle.run { transport.messageCount(chatId.raw, files = true) }
        return VideoCount(videos, files)
    }

    private suspend fun classify(chat: TransportChat, me: Long, switches: DiscoverySwitches): ChatKind? = when (chat.type) {
        TransportChatType.Channel -> ChatKind.Channel
        TransportChatType.Group -> ChatKind.Group
        TransportChatType.Secret -> null
        TransportChatType.Private -> when {
            chat.userId == me -> ChatKind.SavedMessages.takeIf { switches.includeSavedMessages }
            chat.userId == TELEGRAM_SERVICE_USER -> null
            !switches.includePersonalChats -> null
            throttle.run { transport.isBot(chat.userId) } -> null
            else -> ChatKind.Personal
        }
    }

    private companion object {
        const val TELEGRAM_SERVICE_USER = 777000L
    }
}
