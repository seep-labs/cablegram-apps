import CablegramCore
import Observation
import SwiftUI
import UIKit

// Phone only (spec 008, US3). Reads messages only from the chats the owner chose, downloads thumbnails only, and never plays a video. Adding
// forwards into the library channel through `TelegramDiscovery`.

/// What the picker needs from the app, so the model stays free of the view model.
struct DiscoveryPickerHooks {
    var tombstones: () async -> [String: String]?
    var restore: (String) async -> Bool
    var afterAdd: () -> Void
}

@MainActor @Observable
final class DiscoveryPickerModel {
    enum Adding: Equatable {
        case idle
        case working(done: Int, total: Int)
        case finished(added: Int, failed: [DiscoveryVideo])
    }

    enum CellState { case open, inLibrary, deleting, protected }

    struct Key: Hashable { var chat: Int64; var message: Int64 }

    private(set) var chats: [StoredChoice]
    /// Chats dropped from the choice because the owner left them or lost access (one-line note).
    private(set) var dropped = 0
    private(set) var filter: Int64?
    private(set) var videos: [DiscoveryVideo] = []
    private(set) var thumbs: [Key: String] = [:]
    private(set) var selected: Set<Key> = []
    private(set) var addedKeys: Set<String> = []
    private(set) var loading = false
    private(set) var exhausted = false
    private(set) var tombstones: [String: String] = [:]
    private(set) var adding: Adding = .idle
    private(set) var confirmReadd: [DiscoveryVideo]?

    @ObservationIgnored private let graph: DiscoveryGraph
    @ObservationIgnored private let hooks: DiscoveryPickerHooks
    @ObservationIgnored private var pager: TelegramDiscovery.VideoPager?
    @ObservationIgnored private var loadJob: Task<Void, Never>?
    @ObservationIgnored private var thumbJobs: [Task<Void, Never>] = []
    private static let page = 50

    init(graph: DiscoveryGraph, hooks: DiscoveryPickerHooks) {
        self.graph = graph; self.hooks = hooks
        chats = graph.store.snapshot().choice
        Task { if let states = await hooks.tombstones() { tombstones = states } }
        Task {
            // A chat the owner left, or lost access to, leaves the choice before anything is read from it.
            dropped = await graph.controller.dropUnreadable { (try? await graph.discovery.isReadable($0)) ?? false }
            chats = graph.store.snapshot().choice
            show(nil)
        }
    }

    static func key(_ v: DiscoveryVideo) -> Key { Key(chat: v.chatId.raw, message: v.messageId) }

    func show(_ chatId: Int64?) {
        loadJob?.cancel()
        loading = false
        filter = chatId
        videos = []; selected = []; exhausted = false
        let ids = Set((chatId.map { [$0] } ?? chats.map(\.chatId)).map(DiscoveryChatId.init))
        pager = ids.isEmpty ? nil : try? graph.discovery.videoPager(chatIds: ids)
        more()
    }

    /// The next 50 videos, newest first (FR-009).
    func more() {
        guard let pager else { exhausted = true; return }
        if loading { return }
        loading = true
        loadJob = Task {
            defer { loading = false }
            do {
                let page = try await pager.next(limit: Self.page)
                videos += page
                exhausted = pager.exhausted
                // One thumbnail failing (expired file reference, chat just removed) leaves a placeholder; it must not end the picker.
                for video in page {
                    thumbJobs.append(Task {
                        if let path = try? await graph.discovery.thumbnail(for: video) { thumbs[Self.key(video)] = path }
                    })
                }
            } catch is CancellationError {
            } catch {
                exhausted = true
            }
        }
    }

    func state(of v: DiscoveryVideo, inLibrary: Set<String>) -> CellState {
        if addedKeys.contains(v.libraryKey) || inLibrary.contains(v.libraryKey) { return .inLibrary }
        if tombstones[v.libraryKey] == "deleting" { return .deleting }
        return v.protectedContent ? .protected : .open
    }

    func toggle(_ v: DiscoveryVideo, inLibrary: Set<String>) {
        guard state(of: v, inLibrary: inLibrary) == .open else { return }
        if selected.contains(Self.key(v)) { selected.remove(Self.key(v)) } else { selected.insert(Self.key(v)) }
    }

    /// **Add N to library**. Titles hidden or deleted earlier need the owner's say-so first.
    func add() {
        let picked = videos.filter { selected.contains(Self.key($0)) }
        let again = picked.filter { tombstones[$0.libraryKey] != nil }
        if !again.isEmpty && confirmReadd == nil { confirmReadd = again; return }
        send(picked)
    }

    func confirmAgain() {
        let again = confirmReadd ?? []
        confirmReadd = nil
        Task {
            var restored: [String] = []
            for video in again where await hooks.restore(video.libraryKey) { restored.append(video.libraryKey) }
            for key in restored { tombstones[key] = nil }
            send(videos.filter { selected.contains(Self.key($0)) && tombstones[$0.libraryKey] == nil })
        }
    }

    func cancelAgain() { confirmReadd = nil }

    func retry() {
        guard case let .finished(_, failed) = adding else { return }
        let again = failed.filter { !$0.protectedContent }
        if !again.isEmpty { send(again) }
    }

    func dismissResult() { adding = .idle }
    func dispose() { loadJob?.cancel(); thumbJobs.forEach { $0.cancel() } }

    private func send(_ picked: [DiscoveryVideo]) {
        guard !picked.isEmpty else { return }
        adding = .working(done: 0, total: picked.count)
        Task {
            let outcomes: [ForwardOutcome]
            do { outcomes = try await graph.discovery.forwardToLibrary(picked) } catch { outcomes = picked.map { .failed($0, reason: "\(type(of: error))") } }
            var ok: [DiscoveryVideo] = [], failed: [DiscoveryVideo] = []
            for outcome in outcomes { switch outcome { case let .added(v): ok.append(v); case let .failed(v, _): failed.append(v) } }
            addedKeys.formUnion(ok.map(\.libraryKey))
            selected.subtract(ok.map(Self.key))
            adding = .finished(added: ok.count, failed: failed)
            if !ok.isEmpty { hooks.afterAdd() }
        }
    }
}

struct DiscoveryPickerView: View {
    let viewModel: PhoneViewModel
    let onClose: () -> Void
    @State private var model: DiscoveryPickerModel
    @State private var showHow = false

    init(graph: DiscoveryGraph, telegram: TelegramController, viewModel: PhoneViewModel, onClose: @escaping () -> Void) {
        self.viewModel = viewModel; self.onClose = onClose
        _model = State(initialValue: DiscoveryPickerModel(graph: graph, hooks: DiscoveryPickerHooks(
            tombstones: { await telegram.discoveryTombstoneStates() }, restore: { await telegram.discoveryRestore($0) }, afterAdd: { telegram.afterDiscoveryAdd() })))
    }

    private var inLibrary: Set<String> { Set(viewModel.items.compactMap(\.telegramFileKey)) }

    var body: some View {
        let library = inLibrary
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(key: "picker_title").font(PhoneFont.titleLarge).foregroundStyle(.white)
                Spacer()
                Button { onClose() } label: { Text(key: "picker_done") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.pickerDone)
            }
            if model.dropped > 0 { Text(verbatim: pluralText("picker_dropped", model.dropped)).font(.system(.footnote)).foregroundStyle(Color.phoneAccent) }
            banner
            if model.chats.count > 1 { chips }
            ZStack {
                if model.videos.isEmpty && !model.loading { Text(key: "picker_empty").foregroundStyle(Color.phoneMuted).frame(maxHeight: .infinity, alignment: .top) }
                ScrollView {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                        ForEach(model.videos, id: \.self) { video in Cell(model: model, video: video, inLibrary: library) }
                    }
                    if !model.exhausted {
                        Button { model.more() } label: { Text(key: "picker_more") }.buttonStyle(OutlinedButtonStyle()).disabled(model.loading)
                            .padding(.top, 10).accessibilityIdentifier(MaestroIds.pickerMore)
                    }
                }
                .accessibilityIdentifier(MaestroIds.pickerGrid)
            }
            .frame(maxHeight: .infinity)
            bottom
        }
        .padding(16)
        .background(Color.phoneBackground.ignoresSafeArea())
        .onDisappear { model.dispose() }
        .alert(L("picker_readd_title"), isPresented: Binding(get: { model.confirmReadd != nil }, set: { if !$0 { model.cancelAgain() } })) {
            Button(L("picker_readd_confirm")) { model.confirmAgain() }
            Button(L("picker_cancel"), role: .cancel) { model.cancelAgain() }
        } message: { Text(verbatim: L("picker_readd_body", model.confirmReadd?.count ?? 0)) }
        .alert(L("how_title"), isPresented: $showHow) {
            Button(L("how_link")) { if let url = URL(string: Self.surfaceTestURL) { UIApplication.shared.open(url) } }
            Button(L("picker_done"), role: .cancel) {}
        } message: { Text(verbatim: L("ios_discovery_how_body")).accessibilityIdentifier(MaestroIds.pickerHowPage) }
    }

    /// US4: shown every time the picker opens, above the grid, with no way to dismiss it (FR-011).
    private var banner: some View {
        let personal = model.chats.contains { $0.kind == .personal || $0.kind == .savedMessages }
        let bots = model.chats.contains { $0.kind == .bot }
        let key = personal && bots ? "picker_banner_personal_bots" : bots ? "picker_banner_bots" : personal ? "picker_banner_personal" : "picker_banner"
        return VStack(alignment: .leading, spacing: 2) {
            Text(key: key).font(.system(.footnote)).foregroundStyle(.white)
            Button { showHow = true } label: { Text(key: "picker_how") }.buttonStyle(AccentTextButtonStyle()).accessibilityIdentifier(MaestroIds.pickerHow)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(hex: 0x1C1E24), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.pickerBanner)
    }

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(L("picker_all"), selected: model.filter == nil) { model.show(nil) }
                ForEach(model.chats, id: \.chatId) { chat in chip(chat.title, selected: model.filter == chat.chatId) { model.show(chat.chatId) } }
            }
        }
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: title).lineLimit(1).font(PhoneFont.labelLarge).padding(.horizontal, 12).padding(.vertical, 8)
                .foregroundStyle(selected ? Color.black : Color.white)
                .background(selected ? Color.phoneAccent : Color(hex: 0x1C1E24), in: Capsule())
        }.buttonStyle(.plain)
    }

    @ViewBuilder private var bottom: some View {
        switch model.adding {
        case let .working(done, total):
            Text(verbatim: L("picker_adding", done, total)).foregroundStyle(Color.phoneMuted).accessibilityIdentifier(MaestroIds.pickerStatus)
        case let .finished(added, failed):
            VStack(alignment: .leading, spacing: 6) {
                if added > 0 { Text(verbatim: pluralText("picker_added", added)).foregroundStyle(.white) }
                if !failed.isEmpty {
                    Text(verbatim: L("picker_failed", failed.count)).foregroundStyle(Color.phoneAccent)
                    Button { model.retry() } label: { Text(key: "picker_try_again") }.buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier(MaestroIds.pickerTryAgain)
                }
            }.accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.pickerStatus)
        case .idle: EmptyView()
        }
        let working: Bool = { if case .working = model.adding { true } else { false } }()
        Button { model.add() } label: { Text(verbatim: pluralText("picker_add", model.selected.count)) }
            .buttonStyle(PrimaryButtonStyle(minHeight: 52)).disabled(model.selected.isEmpty || working).accessibilityIdentifier(MaestroIds.pickerAdd)
    }

    /// The test that pins what discovery may ask Telegram (the iPhone counterpart of Android's `TelegramApiSurfaceTest`).
    static let surfaceTestURL = "https://github.com/seep-labs/cablegram-apps/blob/main/apps/ios-phone/Packages/CablegramCore/Tests/CablegramCoreTests/Discovery/DiscoverySurfaceTests.swift"
}

private struct Cell: View {
    let model: DiscoveryPickerModel
    let video: DiscoveryVideo
    let inLibrary: Set<String>
    @State private var image: UIImage?

    var body: some View {
        let state = model.state(of: video, inLibrary: inLibrary)
        let ticked = model.selected.contains(DiscoveryPickerModel.key(video))
        let path = model.thumbs[DiscoveryPickerModel.key(video)]
        VStack(alignment: .leading, spacing: 4) {
            ZStack {
                Color(hex: 0x1C1E24)
                if let image { Image(uiImage: image).resizable().scaledToFill() }
                if state == .open {
                    Image(systemName: ticked ? "checkmark.square.fill" : "square").font(.title3).foregroundStyle(ticked ? Color.phoneAccent : .white)
                        .shadow(radius: 2).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(8)
                }
                if let badge = badge(state) {
                    Text(verbatim: badge).font(.system(.caption)).foregroundStyle(.white).padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 6)).padding(6)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                        .accessibilityIdentifier(state == .inLibrary ? MaestroIds.pickerInLibrary : "picker_badge")
                }
                if video.durationSeconds > 0 {
                    Text(verbatim: Self.duration(video.durationSeconds)).font(.system(.caption)).foregroundStyle(.white).padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 6)).padding(6)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                }
            }
            .aspectRatio(16.0 / 9.0, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 10))
            Text(verbatim: video.title.isEmpty ? "—" : video.title).font(.system(.footnote)).lineLimit(2)
                .foregroundStyle(state == .open ? Color.white : Color.phoneMuted)
        }
        .contentShape(Rectangle())
        .onTapGesture { model.toggle(video, inLibrary: inLibrary) }
        .accessibilityElement(children: .contain).accessibilityIdentifier(MaestroIds.pickerItem)
        .task(id: path) {
            guard let path else { return }
            image = await Task.detached { UIImage(contentsOfFile: path) }.value
        }
    }

    private func badge(_ state: DiscoveryPickerModel.CellState) -> String? {
        switch state {
        case .inLibrary: L("picker_in_library")
        case .deleting: L("picker_deleting")
        case .protected: L("discovery_protected")
        case .open: nil
        }
    }

    private static func duration(_ seconds: Int) -> String {
        let h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
