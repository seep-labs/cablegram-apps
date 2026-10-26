import CablegramCore
import SwiftUI

private let danger = Color(hex: 0xFF8A80)

/// "3 titles" style text from the plural entries of the catalog.
func pluralText(_ key: String, _ count: Int) -> String {
    String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count)
}

/// What a poster does while titles are being selected: its titles, and whether tapping selects instead of opening.
struct CardSelection {
    let ids: [String]
    let active: Bool
    let selected: Bool
    let toggle: () -> Void
}

extension PhoneViewModel {
    /// The titles "Select all" picks: the opened series' episodes, or everything the library grid lists.
    fileprivate var selectableIds: [String] {
        if let series = shownSeries { return series.matchedItems.map(\.id) }
        return libraryEntries.flatMap(\.matchedItems).map(\.id)
    }

    func selection(for ids: [String]) -> CardSelection {
        CardSelection(ids: ids, active: selecting, selected: isSelected(selectedIds, ids)) { [self] in toggleSelected(ids) }
    }
}

extension View {
    /// A poster: a tap opens it, or toggles it while titles are being selected; a long press starts (or continues) a selection.
    func selectableCard(_ selection: CardSelection?, onOpen: @escaping () -> Void) -> some View {
        modifier(SelectableCard(selection: selection, onOpen: onOpen))
    }
}

private struct SelectableCard: ViewModifier {
    let selection: CardSelection?
    let onOpen: () -> Void

    func body(content: Content) -> some View {
        content
            .contentShape(Rectangle())
            .onTapGesture { if let selection, selection.active { selection.toggle() } else { onOpen() } }
            .onLongPressGesture(minimumDuration: 0.4) { selection?.toggle() }
            .accessibilityAddTraits(.isButton)
            .accessibilityAddTraits(selection?.selected == true ? .isSelected : [])
            .accessibilityAction(named: L("library_select_action")) { selection?.toggle() }
    }
}

/// Dims a selected poster and marks it with a check.
struct SelectionMark: View {
    let selection: CardSelection?

    var body: some View {
        if selection?.selected == true {
            ZStack(alignment: .bottomLeading) {
                Color.black.opacity(0.4)
                Image(systemName: "checkmark.circle.fill").font(.system(size: 24)).foregroundStyle(Color.phoneAccent).padding(8)
                    .accessibilityLabel(L("library_selected_item"))
            }
        }
    }
}

/// The selection bars, any banners and the sheets. It lives above the library and the series page, so one selection works on both.
struct LibrarySelectionLayer: View {
    let viewModel: PhoneViewModel

    var body: some View {
        @Bindable var vm = viewModel
        ZStack {
            VStack(spacing: 0) {
                if viewModel.selecting { SelectionTopBar(viewModel: viewModel) }
                Spacer(minLength: 0)
                VStack(spacing: 8) {
                    BulkBanners(viewModel: viewModel)
                    if viewModel.selecting { SelectionActionBar(viewModel: viewModel) }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
            }
        }
        .allowsHitTesting(viewModel.selecting || viewModel.bulkProgress != nil || viewModel.undoRemoval != nil || viewModel.bulkFailure != nil)
        .sheet(isPresented: Binding(get: { viewModel.bulkSheet == .addTo }, set: { if !$0 { viewModel.bulkSheet = .none } })) { AddToSheet(viewModel: viewModel) }
        .sheet(isPresented: Binding(get: { viewModel.bulkSheet == .freeUp }, set: { if !$0 { viewModel.bulkSheet = .none } })) { FreeUpSheet(viewModel: viewModel) }
        .sheet(isPresented: Binding(get: { viewModel.bulkSheet == .remove }, set: { if !$0 { viewModel.bulkSheet = .none } })) { RemoveSheet(viewModel: viewModel) }
    }
}

private struct SelectionTopBar: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let selected = viewModel.selectedItems
        let all = viewModel.selectableIds
        HStack(spacing: 4) {
            Button { viewModel.clearSelection() } label: {
                Image(systemName: "xmark").foregroundStyle(.white).frame(width: 44, height: 44)
            }
            .accessibilityLabel(L("library_exit_selection")).accessibilityIdentifier("library_selection_close")
            Text(L("library_selected_count", selected.count)).font(PhoneFont.titleMedium).foregroundStyle(.white)
                .accessibilityLabel(L("library_selected_announce", selected.count, max(all.count, selected.count)))
                .accessibilityIdentifier("library_selected_count")
            Spacer()
            // A single title keeps its own menu (Play, Save to cloud, Edit details…), since long-press now selects.
            if let only = selected.first, selected.count == 1 {
                Menu { ItemMenu(item: only, viewModel: viewModel) } label: {
                    Image(systemName: "ellipsis.circle").foregroundStyle(.white).frame(width: 44, height: 44)
                }
            }
            Button { viewModel.selectAll(all) } label: { Text(key: "library_select_all").font(PhoneFont.labelLarge).foregroundStyle(Color.phoneAccent).padding(.horizontal, 8).frame(minHeight: 44) }
                .accessibilityIdentifier("library_select_all")
        }
        .padding(.horizontal, 8)
        .background(Color.phonePanel)
    }
}

private struct SelectionActionBar: View {
    let viewModel: PhoneViewModel

    var body: some View {
        HStack {
            actionButton("folder", "library_action_add_to", "library_action_add_to", Color.phoneAccent) { viewModel.bulkSheet = .addTo }
            actionButton("iphone", "library_action_free_up", "library_action_free_up", Color.phoneAccent) { viewModel.bulkSheet = .freeUp }
            actionButton("trash", "library_action_remove", "library_action_remove", danger) { viewModel.bulkSheet = .remove }
        }
        .padding(.horizontal, 4)
        .background(Color.phonePanel, in: RoundedRectangle(cornerRadius: 16))
    }

    private func actionButton(_ symbol: String, _ key: String, _ id: String, _ tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: symbol).foregroundStyle(tint)
                Text(key: key).font(PhoneFont.labelLarge).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 52)
        }
        .accessibilityIdentifier(id)
    }
}

private struct BulkBanners: View {
    let viewModel: PhoneViewModel

    var body: some View {
        if let message = viewModel.bulkProgress {
            banner {
                ProgressView().tint(.phoneAccent)
                Text(message).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityIdentifier("library_bulk_progress")
        }
        if let pending = viewModel.undoRemoval {
            banner {
                Text(pluralText("library_removed_count", pending.items.count)).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
                Button { viewModel.undoBulkRemoval() } label: { Text(key: "library_undo").foregroundStyle(Color.phoneAccent).frame(minHeight: 44) }
                    .accessibilityIdentifier("library_undo")
            }
            .accessibilityIdentifier("library_undo_banner")
        }
        if let failure = viewModel.bulkFailure {
            banner {
                Text(L("library_bulk_result", failure.removed, failure.items.count)).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
                Button { viewModel.retryBulkRemoval() } label: { Text(key: "library_retry").foregroundStyle(Color.phoneAccent).frame(minHeight: 44) }
                    .accessibilityIdentifier("library_retry")
                Button { viewModel.dismissBulkFailure() } label: { Image(systemName: "xmark").foregroundStyle(Color.phoneMuted).frame(width: 44, height: 44) }
                    .accessibilityLabel(L("ui_cancel_19766e"))
            }
            .accessibilityIdentifier("library_bulk_failure")
        }
    }

    private func banner<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 8, content: content)
            .font(PhoneFont.bodyMedium)
            .padding(.horizontal, 16).frame(minHeight: 48)
            .background(Color(hex: 0x2A2D35), in: RoundedRectangle(cornerRadius: 12))
            .accessibilityElement(children: .contain)
    }
}

private struct AddToSheet: View {
    let viewModel: PhoneViewModel
    @State private var newName = ""

    var body: some View {
        let inFilter = viewModel.collections.filter { viewModel.libraryFilters.collectionIds.contains($0.id) }
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(key: "library_add_to_title").font(PhoneFont.titleMedium).foregroundStyle(.white)
                ForEach(viewModel.collections) { collection in
                    Button { viewModel.bulkAddToCollection(collection) } label: {
                        Text(collection.name).foregroundStyle(.white).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    }
                    .accessibilityIdentifier("library_add_to_\(collection.id)")
                }
                // While the grid is filtered by collections, the same titles can be taken out of them again.
                ForEach(inFilter) { collection in
                    Button { viewModel.bulkRemoveFromCollection(collection) } label: {
                        Text(L("library_remove_from_collection", collection.name)).foregroundStyle(danger).frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    }
                    .accessibilityIdentifier("library_remove_from_\(collection.id)")
                }
                PhoneTextField(label: L("ui_collection_name_922214"), text: $newName).accessibilityIdentifier("library_add_to_new_name")
                Button { viewModel.bulkAddToNewCollection(newName) } label: { Text(key: "ui_create_collection_69152b").frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("library_add_to_new")
            }
            .padding(20)
        }
        .background(Color.phonePanel.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

private struct FreeUpSheet: View {
    let viewModel: PhoneViewModel

    var body: some View {
        let summary = summarizeFreeUp(viewModel.selectedItems)
        let total = summary.eligible.count + summary.skipped.count
        VStack(alignment: .leading, spacing: 8) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text(key: "library_free_up_title").font(PhoneFont.titleMedium).foregroundStyle(.white)
                    if summary.eligible.isEmpty {
                        Text(key: "library_free_up_none").foregroundStyle(Color.phoneMuted)
                    } else {
                        Text(L("library_free_up_summary", summary.eligible.count, total, formatBytes(summary.bytes))).foregroundStyle(Color.phoneMuted)
                    }
                    if !summary.skipped.isEmpty && !summary.eligible.isEmpty {
                        Text(L("library_free_up_skipped", summary.skipped.count)).foregroundStyle(Color.phoneMuted)
                    }
                    ForEach(summary.skipped.prefix(6)) { Text("• \($0.title)").font(PhoneFont.labelMedium).foregroundStyle(Color.phoneMuted) }
                }
                .padding(.top, 20)
            }
            HStack(spacing: 12) {
                Button { viewModel.bulkSheet = .none } label: { Text(key: "ui_cancel_19766e").frame(maxWidth: .infinity) }.buttonStyle(OutlinedButtonStyle())
                Button { viewModel.confirmBulkFreeUp() } label: { Text(key: "library_action_free_up").frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48)).disabled(summary.eligible.isEmpty).accessibilityIdentifier("library_free_up_confirm")
            }
        }
        .padding(.horizontal, 20).padding(.bottom, 20)
        .background(Color.phonePanel.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

private struct RemoveSheet: View {
    let viewModel: PhoneViewModel
    @State private var mode = RemovalMode.removeFromLibrary

    var body: some View {
        let summary = summarizeRemoval(viewModel.selectedItems)
        VStack(alignment: .leading, spacing: 8) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text(pluralText("library_remove_title_count", summary.total)).font(PhoneFont.titleMedium).foregroundStyle(.white)
                    if summary.phoneItems > 0 { Text(pluralText("library_summary_phone", summary.phoneItems)).foregroundStyle(Color.phoneMuted) }
                    if summary.telegramItems > 0 { Text(pluralText("library_summary_telegram", summary.telegramItems)).foregroundStyle(Color.phoneMuted) }
                    choice(.removeFromLibrary, L("ui_remove_from_library_8e7161"), "library_remove_mode_keep") {
                        Text(key: "library_remove_keep_desc").font(PhoneFont.labelMedium).foregroundStyle(Color.phoneMuted)
                    }
                    choice(.deleteFiles, L("library_remove_delete_label"), "library_remove_mode_delete") {
                        if summary.phoneItems > 0 { Text(L("library_remove_delete_phone", summary.phoneItems, formatBytes(summary.phoneBytes))).font(PhoneFont.labelMedium).foregroundStyle(Color.phoneMuted) }
                        if summary.telegramItems > 0 { Text(L("library_remove_delete_telegram", summary.telegramItems)).font(PhoneFont.labelMedium).foregroundStyle(Color.phoneMuted) }
                        Text(key: "library_remove_irreversible").font(PhoneFont.labelMedium).foregroundStyle(danger)
                    }
                }
                .padding(.top, 20)
            }
            HStack(spacing: 12) {
                Button { viewModel.bulkSheet = .none } label: { Text(key: "ui_cancel_19766e").frame(maxWidth: .infinity) }.buttonStyle(OutlinedButtonStyle())
                Button { viewModel.confirmBulkRemove(mode) } label: { Text(key: "ui_remove_c3812f").frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48)).accessibilityIdentifier("library_remove_confirm")
            }
        }
        .padding(.horizontal, 20).padding(.bottom, 20)
        .background(Color.phonePanel.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func choice<Details: View>(_ value: RemovalMode, _ label: String, _ id: String, @ViewBuilder details: () -> Details) -> some View {
        Button { mode = value } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: mode == value ? "largecircle.fill.circle" : "circle").foregroundStyle(mode == value ? Color.phoneAccent : Color.phoneMuted).padding(.top, 2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(PhoneFont.bodyLarge).foregroundStyle(.white)
                    details()
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 8).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(mode == value ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}
