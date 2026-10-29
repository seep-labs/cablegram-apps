import CablegramCore
import Observation
import SwiftUI

// Phone only (spec 008, US1 and US2). The screens never touch Telegram directly: every read goes through `DiscoveryGraph`.

/// Everything the Find videos screen shows; the scan itself runs here, never in a view.
@MainActor @Observable
final class FindVideosModel {
    enum Phase { case asking, scanning, done, failed, managing }

    private(set) var phase: Phase = .asking
    private(set) var checked = 0
    private(set) var total = 0
    private(set) var pausedSeconds: Int?
    /// Chats the last scan did not read again because nothing new was posted in them.
    private(set) var unchanged = 0
    var showEmpty = false
    /// Part of a chat name the owner typed; the list shows only chats whose name contains it.
    var query = ""
    private(set) var rows: [StoredScanRow]
    /// Every box starts unticked; only a saved choice is ticked when the list is reopened (FR-008).
    private(set) var ticked: Set<Int64>
    /// The switch under the search box; chats with bots are left out until the owner turns it on and confirms.
    private(set) var includeBots: Bool
    private(set) var askingBots = false

    @ObservationIgnored private let graph: DiscoveryGraph
    @ObservationIgnored private var job: Task<Void, Never>?

    init(graph: DiscoveryGraph) {
        self.graph = graph
        rows = graph.store.snapshot().scan
        ticked = graph.store.choice()
        includeBots = graph.store.switches().includeBotChats
    }

    var hasChoice: Bool { !graph.store.choice().isEmpty }
    var extraKinds: Bool { graph.store.switches().any }
    func matches(_ title: String) -> Bool { query.trimmingCharacters(in: .whitespaces).isEmpty || title.localizedCaseInsensitiveContains(query.trimmingCharacters(in: .whitespaces)) }

    func askBots() { askingBots = true }
    func cancelBots() { askingBots = false }

    /// The confirmation is the permission for this look: it saves the switch and scans again, with a fresh permit.
    func lookInBots() {
        askingBots = false
        Task {
            await stopJob()
            var switches = graph.store.switches()
            switches.includeBotChats = true
            await graph.controller.setSwitches(switches)
            includeBots = true
            look()
        }
    }

    func stopBots() {
        Task {
            await stopJob()
            var switches = graph.store.switches()
            switches.includeBotChats = false
            await graph.controller.setSwitches(switches)
            includeBots = false
            rows.removeAll { $0.kind == .bot }
            ticked.formIntersection(rows.map(\.chatId))
            if phase == .scanning { phase = .done }
        }
    }

    /// **Manage chats** reads only what is already stored on the phone, so it asks for nothing.
    func manage() { phase = .managing }

    func toggle(_ chatId: Int64) { if ticked.contains(chatId) { ticked.remove(chatId) } else { ticked.insert(chatId) } }

    /// **Use N chats**: saves the choice and purges whatever was unticked.
    func useChats(onSaved: @escaping @MainActor () -> Void) {
        let chosen = rows.filter { ticked.contains($0.chatId) }.map { DiscoveryChat(id: DiscoveryChatId($0.chatId), kind: $0.kind, title: $0.title, protectedContent: $0.protectedContent) }
        Task {
            // The owner need not wait for the scan: Use stops it, and what it read stays for the next Look (US1 acceptance 5).
            await stopJob()
            await graph.controller.useChats(chosen)
            onSaved()
        }
    }

    /// Only the prompt's **Look for videos** calls this, and it asks the controller for a fresh permit each time (FR-003).
    func look() {
        let permit = graph.controller.grantScan()
        phase = .scanning
        pausedSeconds = nil
        unchanged = 0
        job = Task { await scan(permit) }
    }

    func stop() { job?.cancel() }
    func dispose() { job?.cancel(); graph.controller.endScan() }
    func decline() { phase = .done }

    private func stopJob() async {
        job?.cancel()
        await job?.value
        job = nil
    }

    private func scan(_ permit: ScanPermit) async {
        let pauses = graph.throttle.pauses
        let pauseWatcher = Task { for await seconds in pauses { self.pausedSeconds = seconds } }
        defer { pauseWatcher.cancel(); graph.controller.endScan() }
        do {
            if let session = PhoneTelegram.existing() {
                // Telegram not ready in time: say so, with a way to try again.
                guard await session.first(timeout: 30, where: { if case .ready = $0 { true } else { false } }) != nil else { phase = .failed; return }
            }
            let state = graph.store.snapshot()
            if state.scanCompleted || state.scanned.isEmpty { graph.store.restartScan() }
            let done = Set(graph.store.snapshot().scanned)
            var chats: [DiscoveryChat] = []
            for try await chat in graph.discovery.discoveryChats(permit: permit, switches: graph.store.switches()) { chats.append(chat) }
            try Task.checkCancellation()
            total = chats.count
            checked = chats.filter { done.contains($0.id.raw) }.count
            let before = Dictionary(graph.store.snapshot().scan.map { ($0.chatId, $0) }, uniquingKeysWith: { first, _ in first })
            let todo = chats.filter { !done.contains($0.id.raw) }
            await withTaskGroup(of: Void.self) { group in
                var next = 0
                while next < min(scanParallelChats, todo.count) { let chat = todo[next]; next += 1; group.addTask { await self.read(chat, permit: permit, known: before[chat.id.raw]) } }
                while await group.next() != nil {
                    if next < todo.count { let chat = todo[next]; next += 1; group.addTask { await self.read(chat, permit: permit, known: before[chat.id.raw]) } }
                }
            }
            try Task.checkCancellation()
            // Chats no longer listed leave the results (a finished scan refreshes in place).
            let listed = Set(chats.map(\.id.raw))
            rows.removeAll { !listed.contains($0.chatId) }
            graph.store.finishScan(completed: true)
            phase = .done
        } catch is CancellationError {
            phase = .done
        } catch {
            phase = .failed
        }
    }

    private func read(_ chat: DiscoveryChat, permit: ScanPermit, known: StoredScanRow?) async {
        // Quiet chats keep the counts of the last look: no request, so dead channels cost nothing.
        if var known, isUnchangedSinceLastLook(known, chat) {
            known.title = chat.title; known.protectedContent = chat.protectedContent
            graph.store.addScanRow(known)
            place(known)
            unchanged += 1
            checked += 1
            return
        }
        let count: VideoCount
        do { count = try await graph.discovery.chatVideoCount(permit: permit, chatId: chat.id) }
        catch is CancellationError { return }
        // One chat that cannot be read (private again, admin only) is skipped; the rest are still counted.
        catch is TgError { checked += 1; return }
        catch { return }
        let row = StoredScanRow(chatId: chat.id.raw, kind: chat.kind, title: chat.title, protectedContent: chat.protectedContent, videos: count.videos, files: count.files,
                                scannedAt: Int64(Date().timeIntervalSince1970 * 1000), lastMessageId: chat.lastMessageId)
        graph.store.addScanRow(row)
        place(row)
        checked += 1
        pausedSeconds = nil
    }

    /// A chat keeps its place in the list: rows must not jump while the owner is ticking.
    private func place(_ row: StoredScanRow) {
        if let at = rows.firstIndex(where: { $0.chatId == row.chatId }) { rows[at] = row } else { rows.append(row) }
    }
}

struct FindVideosView: View {
    let graph: DiscoveryGraph
    let telegram: TelegramController
    let viewModel: PhoneViewModel
    let onClose: () -> Void
    @State private var model: FindVideosModel
    @State private var picking = false

    init(graph: DiscoveryGraph, telegram: TelegramController, viewModel: PhoneViewModel, onClose: @escaping () -> Void) {
        self.graph = graph; self.telegram = telegram; self.viewModel = viewModel; self.onClose = onClose
        _model = State(initialValue: FindVideosModel(graph: graph))
    }

    var body: some View {
        Group {
            if picking { DiscoveryPickerView(graph: graph, telegram: telegram, viewModel: viewModel, onClose: onClose) } else { screen }
        }
        .background(Color.phoneBackground.ignoresSafeArea())
        .onDisappear { model.dispose() }
    }

    private var screen: some View {
        let scanning = model.phase == .scanning || model.phase == .done
        let listing = scanning || model.phase == .managing
        return VStack(alignment: .leading, spacing: 12) {
            Text(key: "discovery_title").font(PhoneFont.titleLarge).foregroundStyle(.white)
            if model.phase == .asking || model.phase == .failed {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        OnThisPhonePrompt(failed: model.phase == .failed, moreKinds: model.extraKinds, onLook: model.look, onNotNow: { model.decline(); onClose() })
                        if model.phase == .asking && model.hasChoice {
                            Button { picking = true } label: { Text(key: "discovery_pick") }.buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.discoveryPick)
                            Button { model.manage() } label: { Text(key: "discovery_manage") }.buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.discoveryManage)
                        }
                    }
                }
            }
            if scanning { progress }
            if listing {
                TextField("", text: Bindable(model).query, prompt: Text(verbatim: L("discovery_search_hint")).foregroundStyle(Color.phoneMuted))
                    .textFieldStyle(.roundedBorder).autocorrectionDisabled().accessibilityIdentifier(MaestroIds.discoverySearch)
                Toggle(isOn: Binding(get: { model.includeBots }, set: { $0 ? model.askBots() : model.stopBots() })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(key: "discovery_bots_switch").foregroundStyle(.white)
                        Text(key: "discovery_bots_hint").font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                    }
                }.tint(.phoneAccent).accessibilityIdentifier(MaestroIds.discoveryBotsSwitch)
                results
                // Available while the scan runs too: the owner need not wait for it (Use stops it).
                Button { model.useChats { if !model.ticked.isEmpty { picking = true } else { onClose() } } } label: { Text(verbatim: pluralText("discovery_use_chats", model.ticked.count)) }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 52)).disabled(model.ticked.isEmpty && !model.hasChoice).accessibilityIdentifier(MaestroIds.discoveryUse)
            }
        }
        .padding(16)
        .alert(L("discovery_bots_title"), isPresented: Binding(get: { model.askingBots }, set: { if !$0 { model.cancelBots() } })) {
            Button(L("discovery_look")) { model.lookInBots() }
            Button(L("picker_cancel"), role: .cancel) { model.cancelBots() }
        } message: { Text(verbatim: L("discovery_notice_body") + "\n\n" + L("discovery_bots_body")) }
    }

    private var progress: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: model.total == 0 && model.phase == .scanning ? L("discovery_listing") : L("discovery_checked", model.checked, model.total))
                    .foregroundStyle(Color.phoneMuted).accessibilityIdentifier(MaestroIds.discoveryProgress)
                if let paused = model.pausedSeconds { Text(verbatim: L("discovery_paused", paused)).font(.system(.caption)).foregroundStyle(Color.phoneAccent) }
                if model.unchanged > 0 { Text(verbatim: pluralText("discovery_unchanged", model.unchanged)).font(.system(.caption)).foregroundStyle(Color.phoneMuted) }
            }
            Spacer()
            if model.phase == .scanning {
                Button { model.stop() } label: { Text(key: "discovery_stop") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.discoveryStop)
            } else {
                Button { onClose() } label: { Text(key: "ui_ok_565339") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.discoveryScanDone)
            }
        }
    }

    private var results: some View {
        let matching = model.rows.filter { model.matches($0.title) }
        // While the scan runs the list keeps its order, so a row does not move as the owner taps it; the biggest chats come first once it is done.
        let withVideos = matching.filter { $0.videos + $0.files > 0 }
        let sorted = model.phase == .scanning ? withVideos : withVideos.sorted { $0.videos + $0.files > $1.videos + $1.files }
        let empty = matching.filter { $0.videos + $0.files == 0 }
        let searching = !model.query.trimmingCharacters(in: .whitespaces).isEmpty
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                if searching && matching.isEmpty { Text(key: "discovery_no_match").foregroundStyle(Color.phoneMuted) }
                ForEach(sorted, id: \.chatId) { row in ScanRow(row: row, ticked: model.ticked.contains(row.chatId)) { model.toggle(row.chatId) } }
                // While searching, every match is listed, with or without videos.
                if searching { ForEach(empty, id: \.chatId) { row in ScanRow(row: row, ticked: model.ticked.contains(row.chatId)) { model.toggle(row.chatId) } } }
                else if !empty.isEmpty {
                    Button { model.showEmpty.toggle() } label: { Text(verbatim: model.showEmpty ? L("discovery_hide_empty") : L("discovery_show_empty", empty.count)) }.buttonStyle(AccentTextButtonStyle())
                    if model.showEmpty { ForEach(empty, id: \.chatId) { row in ScanRow(row: row, ticked: model.ticked.contains(row.chatId)) { model.toggle(row.chatId) } } }
                }
            }
        }
        .accessibilityIdentifier(MaestroIds.discoveryResults)
    }
}

/// The permission prompt (FR-003, US4): shown before every scan, with no "don't ask again".
struct OnThisPhonePrompt: View {
    let failed: Bool
    let moreKinds: Bool
    let onLook: () -> Void
    let onNotNow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(key: "discovery_notice_title").font(PhoneFont.titleMedium).foregroundStyle(.white)
            Text(key: "discovery_notice_body").foregroundStyle(Color.phoneMuted)
            Text(key: "discovery_ask_title").font(PhoneFont.titleMedium).foregroundStyle(.white)
            Text(key: moreKinds ? "discovery_ask_body_more" : "discovery_ask_body").foregroundStyle(Color.phoneMuted)
            if failed { Text(key: "discovery_failed").foregroundStyle(Color.phoneAccent) }
            Button(action: onLook) { Text(key: "discovery_look") }.buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.discoveryLook)
            Button(action: onNotNow) { Text(key: "discovery_not_now") }.buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.discoveryNotNow)
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.discoveryPrompt)
    }
}

private struct ScanRow: View {
    let row: StoredScanRow
    let ticked: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 12) {
                Image(systemName: ticked ? "checkmark.square.fill" : "square").foregroundStyle(ticked ? Color.phoneAccent : Color.phoneMuted).font(.title3)
                    .accessibilityIdentifier(MaestroIds.discoveryChatCheck)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: row.title).foregroundStyle(.white).lineLimit(1)
                    Text(verbatim: detail).font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 4).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine).accessibilityAddTraits(ticked ? .isSelected : [])
    }

    private var detail: String {
        let kindKey: String
        switch row.kind {
        case .channel: kindKey = "discovery_kind_channel"
        case .group: kindKey = "discovery_kind_group"
        case .personal: kindKey = "discovery_kind_personal"
        case .savedMessages: kindKey = "discovery_kind_saved"
        case .bot: kindKey = "discovery_kind_bot"
        }
        let kind = L(kindKey)
        let count = row.files > 0 && row.videos == 0 ? pluralText("discovery_files_maybe", row.files)
            : row.files > 0 ? pluralText("discovery_videos_about", row.videos + row.files) : pluralText("discovery_videos", row.videos)
        return "\(kind) · \(count)" + (row.protectedContent ? " · " + L("discovery_protected") : "")
    }
}
