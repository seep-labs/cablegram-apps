import CablegramCore
import SwiftUI

// Phone only (spec 008, US5). Both switches start off. Turning one on starts nothing; turning one off purges at once.

/// Shortest-video choices in seconds; 0 is any length.
private let minLengths = [300, 600, 900, 1800, 0]

/// **Advanced settings** under Telegram: a collapsible section with the two switches and the shortest video to count.
struct DiscoveryAdvanced: View {
    let graph: DiscoveryGraph
    @State private var open = false
    @State private var switches: DiscoverySwitches
    @State private var minSeconds: Int
    @State private var confirming: Kind?

    private enum Kind: Identifiable { case personal, saved; var id: Int { self == .personal ? 0 : 1 } }

    init(graph: DiscoveryGraph) {
        self.graph = graph
        _switches = State(initialValue: graph.store.switches())
        _minSeconds = State(initialValue: graph.store.minVideoSeconds())
    }

    /// Every change starts from what is stored now: the bots switch lives on another screen and may have changed since this one opened.
    private func apply(_ change: (inout DiscoverySwitches) -> Void) {
        var next = graph.store.switches()
        change(&next)
        switches = next
        Task { await graph.controller.setSwitches(next) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button { open.toggle(); switches = graph.store.switches() } label: { Text(key: "discovery_advanced") }
                .buttonStyle(OutlinedButtonStyle(minHeight: 52)).accessibilityIdentifier(MaestroIds.discoveryAdvanced)
            if open {
                switchRow("advanced_personal_title", "advanced_personal_desc", on: switches.includePersonalChats, id: MaestroIds.advancedPersonal) { on in
                    if on { confirming = .personal } else { apply { $0.includePersonalChats = false } }
                }
                switchRow("advanced_saved_title", "advanced_saved_desc", on: switches.includeSavedMessages, id: MaestroIds.advancedSaved) { on in
                    if on { confirming = .saved } else { apply { $0.includeSavedMessages = false } }
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(key: "advanced_min_title").foregroundStyle(.white)
                    Text(key: "advanced_min_desc").font(.system(.caption)).foregroundStyle(Color.phoneMuted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(minLengths, id: \.self) { seconds in
                                Button { minSeconds = seconds; graph.controller.setMinVideoSeconds(seconds) } label: {
                                    Text(verbatim: seconds == 0 ? L("advanced_min_any") : L("advanced_min_option", seconds / 60)).font(PhoneFont.labelLarge)
                                        .padding(.horizontal, 12).padding(.vertical, 8).foregroundStyle(minSeconds == seconds ? Color.black : Color.white)
                                        .background(minSeconds == seconds ? Color.phoneAccent : Color(hex: 0x1C1E24), in: Capsule())
                                }
                                .buttonStyle(.plain).accessibilityIdentifier(MaestroIds.advancedMinPrefix + String(seconds))
                            }
                        }
                    }
                }.padding(.horizontal, 8)
            }
        }
        .alert(L("advanced_confirm_title"), isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }), presenting: confirming) { kind in
            Button(L("advanced_turn_on")) {
                apply { if kind == .personal { $0.includePersonalChats = true } else { $0.includeSavedMessages = true } }
                confirming = nil
            }
            Button(L("picker_cancel"), role: .cancel) { confirming = nil }
        } message: { kind in
            Text(verbatim: L("discovery_notice_body") + "\n\n" + L(kind == .personal ? "advanced_confirm_personal" : "advanced_confirm_saved"))
        }
    }

    private func switchRow(_ title: String, _ description: String, on: Bool, id: String, onChange: @escaping (Bool) -> Void) -> some View {
        Toggle(isOn: Binding(get: { on }, set: onChange)) {
            VStack(alignment: .leading, spacing: 2) {
                Text(key: title).foregroundStyle(.white)
                Text(key: description).font(.system(.caption)).foregroundStyle(Color.phoneMuted)
            }
        }
        .tint(.phoneAccent).padding(.horizontal, 8).accessibilityIdentifier(id)
    }
}
