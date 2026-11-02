import CablegramCore
import CryptoKit
import Foundation

/// The discovery document (the choice, counts, switches and thumbnail ledger), encrypted with AES-GCM under a key of its own in the Keychain
/// (FR-008). The file sits in `Application Support/telegram`, so `PhoneTelegram.Storage.wipe()` removes it with the Telegram database; `clear()`
/// also deletes the key. Android keeps the same document in Keystore-encrypted preferences.
final class EncryptedFileDiscoveryStorage: DiscoveryStorage, @unchecked Sendable {
    private let lock = NSLock()
    private let secrets = KeychainSecretStore(service: "app.cablegram.phone.discovery")
    private static let keyName = "discovery_key"

    private var url: URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)) ?? FileManager.default.temporaryDirectory
        let folder = base.appendingPathComponent("telegram", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent("discovery.enc")
    }

    func read() -> String? {
        lock.withLock {
            guard case let .found(text) = secrets.lookup(Self.keyName), let keyData = Data(base64Encoded: text), keyData.count == 32,
                  let data = try? Data(contentsOf: url), let box = try? AES.GCM.SealedBox(combined: data),
                  let plain = try? AES.GCM.open(box, using: SymmetricKey(data: keyData)) else { return nil }
            return String(data: plain, encoding: .utf8)
        }
    }

    func write(_ json: String) {
        lock.withLock {
            let key: SymmetricKey
            if case let .found(text) = secrets.lookup(Self.keyName), let data = Data(base64Encoded: text), data.count == 32 { key = SymmetricKey(data: data) }
            else {
                key = SymmetricKey(size: .bits256)
                secrets.write(Self.keyName, key.withUnsafeBytes { Data($0) }.base64EncodedString())
            }
            guard let sealed = try? AES.GCM.seal(Data(json.utf8), using: key).combined else { return }
            var target = url
            try? sealed.write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? target.setResourceValues(values)
        }
    }

    func clear() {
        lock.withLock {
            try? FileManager.default.removeItem(at: url)
            secrets.write(Self.keyName, nil)
        }
    }
}
