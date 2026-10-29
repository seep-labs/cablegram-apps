import CablegramCore
import SwiftUI

/// "Clean up your library": the titles that cannot play from this phone, by why. Selecting hands them to the library's multi-select, whose
/// Remove action has an Undo window; titles that only need a connection can be fixed from here instead.
struct CleanupSheet: View {
    let viewModel: PhoneViewModel
    let close: () -> Void

    var body: some View {
        let groups = viewModel.cleanupProblems
        let total = groups.reduce(0) { $0 + $1.items.count }
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if groups.isEmpty {
                        Text(key: "ios_cleanup_nothing").foregroundStyle(Color.phoneMuted)
                    } else {
                        Text(key: "ios_cleanup_intro").foregroundStyle(Color.phoneMuted)
                        Button { select(groups.flatMap(\.items)) } label: { Text(verbatim: L("ios_cleanup_select_all", total)) }
                            .buttonStyle(PrimaryButtonStyle(minHeight: 52)).accessibilityIdentifier("cleanup_select_all")
                        ForEach(groups, id: \.problem) { group in row(group) }
                    }
                }
                .padding(20)
            }
            .background(Color.phoneBackground)
            .navigationTitle(L("ios_cleanup_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("ui_close_7d9eb7"), action: close) } }
        }
        .preferredColorScheme(.dark)
    }

    private func row(_ group: CleanupGroup) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.circle.fill").foregroundStyle(group.problem.needsConnection ? Color.telegramWarning : Color.problemRed)
                Text(verbatim: groupTitle(group.problem)).font(PhoneFont.titleMedium).foregroundStyle(.white)
                Spacer()
                Text(verbatim: "\(group.items.count)").font(PhoneFont.titleMedium).foregroundStyle(Color.phoneMuted)
            }
            HStack(spacing: 12) {
                Button { select(group.items) } label: { Text(key: "ios_cleanup_select") }
                    .buttonStyle(OutlinedButtonStyle(minHeight: 44)).accessibilityIdentifier("cleanup_select_\(group.problem.identifier)")
                if group.problem.needsConnection {
                    Button { connect(group.problem) } label: { Text(key: "ios_cleanup_connect") }
                        .buttonStyle(PrimaryButtonStyle(minHeight: 44)).accessibilityIdentifier("cleanup_connect_\(group.problem.identifier)")
                }
            }
        }
        .padding(14).background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 16))
    }

    private func groupTitle(_ problem: LibraryProblem) -> String {
        switch problem {
        case .unavailable, .notOnPhone: problem.label
        case .telegramNotConnected: L("ios_cleanup_group_telegram")
        case .cloudNotConnected: L("ios_cleanup_group_cloud")
        }
    }

    /// Selecting is the whole job here: the multi-select bar offers Remove (with Undo), Add to collection and the rest.
    private func select(_ items: [LibraryItem]) {
        viewModel.selectAll(items.map(\.id))
        close()
    }

    private func connect(_ problem: LibraryProblem) {
        close()
        switch problem {
        case .telegramNotConnected: viewModel.telegram.openSheet()
        case .cloudNotConnected: viewModel.selectTab(.storage)
        default: break
        }
    }
}
