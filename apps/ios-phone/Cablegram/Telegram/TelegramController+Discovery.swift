import CablegramCore
import Foundation

// Phone only (spec 008). What the discovery screens need from the app: the graph for this library channel, the titles hidden from the library,
// and a library refresh after videos were forwarded. The screens never reach Telegram except through `DiscoveryGraph`.
extension TelegramController {
    /// Video discovery for this library channel, on the phone's one TDLib client; nil until the Telegram session exists.
    func discoveryGraph() -> DiscoveryGraph? {
        guard let chatId = link?.chatId else { return nil }
        _ = PhoneTelegram.session()
        return PhoneTelegram.discovery(libraryChatId: chatId)
    }

    /// Hidden or deleted Telegram titles as `tgfile:` key to state ("hidden", "deleting"); nil when the catalog can't be reached.
    func discoveryTombstoneStates() async -> [String: String]? {
        guard let token = pairing.accountToken, !token.isEmpty else { return nil }
        guard let tombstones = await catalogClient().telegramTombstones(token: token) else { return nil }
        return Dictionary(tombstones.map { ($0.stableSourceKey, $0.state) }, uniquingKeysWith: { first, _ in first })
    }

    /// The owner confirmed adding a hidden or deleted title again.
    func discoveryRestore(_ stableSourceKey: String) async -> Bool {
        guard let token = pairing.accountToken, !token.isEmpty else { return false }
        return await catalogClient().restoreTelegramTitle(token: token, stableSourceKey: stableSourceKey)
    }

    /// Videos were forwarded into the library channel; bring the library up to date.
    func afterDiscoveryAdd() {
        Task {
            await syncLibrary()
            refresh()
        }
    }
}
