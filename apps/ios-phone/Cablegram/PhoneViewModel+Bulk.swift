import CablegramCore
import Foundation

enum BulkSheet { case none, addTo, freeUp, remove }

/// "Remove from library" that can still be undone; the Telegram hides are sent when the window closes.
struct UndoRemoval { var items: [LibraryItem] }

/// Titles a bulk removal could not finish, kept for Retry.
struct BulkFailure { var mode: RemovalMode; var items: [LibraryItem]; var removed: Int }

private let undoWindow: Duration = .seconds(8)

extension PhoneViewModel {
    var selectedItems: [LibraryItem] { items.filter { selectedIds.contains($0.id) } }
    var selecting: Bool { items.contains { selectedIds.contains($0.id) } }

    /// What the library grid lists right now: the remembered filters and sort plus the search text.
    var libraryEntries: [LibraryEntry] {
        applyLibraryQuery(items, libraryFilters.withoutUnknownCollections(Set(collections.map(\.id))).searching(searchQuery))
    }

    func toggleSelected(_ ids: [String]) { selectedIds = toggleSelection(selectedIds, ids) }
    func selectAll(_ ids: [String]) { selectedIds = Set(ids) }
    func clearSelection() { if !selectedIds.isEmpty { selectedIds = [] } }

    // MARK: Collections

    func bulkAddToCollection(_ collection: UserCollection) {
        for item in selectedItems where !item.collectionIds.contains(collection.id) {
            store.setItemCollections(item.id, collectionIds: item.collectionIds + [collection.id])
        }
        refresh()
        bulkSheet = .none
        clearSelection()
        status = L("library_added_to", collection.name)
    }

    func bulkAddToNewCollection(_ name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        bulkAddToCollection(store.addCollection(clean))
    }

    func bulkRemoveFromCollection(_ collection: UserCollection) {
        for item in selectedItems where item.collectionIds.contains(collection.id) {
            store.setItemCollections(item.id, collectionIds: item.collectionIds.filter { $0 != collection.id })
        }
        refresh()
        bulkSheet = .none
        clearSelection()
        status = L("library_removed_from", collection.name)
    }

    // MARK: Free up space

    /// Only titles with a verified copy elsewhere are freed (`canRemoveLocalCopy`); the sheet already listed the rest.
    func confirmBulkFreeUp() {
        let summary = summarizeFreeUp(selectedItems)
        bulkSheet = .none
        clearSelection()
        guard !summary.eligible.isEmpty else { return }
        Task {
            for (index, item) in summary.eligible.enumerated() {
                bulkProgress = L("library_bulk_freeing", index + 1, summary.eligible.count)
                if canRemoveLocalCopy(item) {
                    store.removeLocalCopy(item.id)
                    store.updateItem(item.id) { var i = $0; i.sourceAvailable = false; return i }
                }
                await Task.yield()
            }
            bulkProgress = nil
            refresh()
            status = L("library_freed_done", formatBytes(summary.bytes))
        }
    }

    // MARK: Removing

    func confirmBulkRemove(_ mode: RemovalMode) {
        let targets = selectedItems
        bulkSheet = .none
        clearSelection()
        guard !targets.isEmpty else { return }
        finalizePendingRemoval()
        bulkFailure = nil
        targets.forEach(telegram.didRemove)
        switch mode {
        case .removeFromLibrary: removeWithUndo(targets)
        case .deleteFiles: deleteFiles(targets)
        }
    }

    private func removeWithUndo(_ targets: [LibraryItem]) {
        targets.forEach { store.removeFromLibrary($0.id) }
        refresh()
        undoRemoval = UndoRemoval(items: targets)
        undoTask = Task { [weak self] in
            try? await Task.sleep(for: undoWindow)
            guard !Task.isCancelled else { return }
            await self?.finishRemoval(targets)
        }
    }

    /// A second removal, or the end of the Undo window, commits the first one now.
    private func finalizePendingRemoval() {
        guard let pending = undoRemoval else { return }
        undoTask?.cancel()
        Task { await finishRemoval(pending.items) }
    }

    func undoBulkRemoval() {
        guard let pending = undoRemoval else { return }
        undoTask?.cancel()
        undoRemoval = nil
        store.restoreRemoved(pending.items)
        refresh()
    }

    /// The Undo window closed: hide the Telegram titles on the control plane, then sync once.
    private func finishRemoval(_ targets: [LibraryItem]) async {
        undoRemoval = nil
        let telegramTitles = targets.filter { $0.sourceKind == "telegram" }
        let failed = await telegram.removeTitlesRemotely(telegramTitles, permanently: false, progress: nil)
        // A sync during the Undo window may have brought the hidden titles back; drop the ones the server now hides.
        for item in telegramTitles where !failed.contains(where: { $0.id == item.id }) { store.removeFromLibrary(item.id) }
        refresh()
        if !failed.isEmpty { bulkFailure = BulkFailure(mode: .removeFromLibrary, items: failed, removed: targets.count - failed.count) }
        await syncLibrary()
    }

    private func deleteFiles(_ targets: [LibraryItem]) {
        Task {
            let total = targets.count
            let phone = targets.filter { $0.sourceKind != "telegram" }, telegramTitles = targets.filter { $0.sourceKind == "telegram" }
            bulkProgress = L("library_bulk_removing", 0, total)
            phone.forEach { store.deleteFile($0.id) }
            bulkProgress = L("library_bulk_removing", phone.count, total)
            let failed = await telegram.removeTitlesRemotely(telegramTitles, permanently: true) { [weak self] done in
                self?.bulkProgress = L("library_bulk_removing", phone.count + done, total)
            }
            for item in telegramTitles where !failed.contains(where: { $0.id == item.id }) { store.removeFromLibrary(item.id) }
            refresh()
            bulkProgress = nil
            telegram.processDeletions()
            if failed.isEmpty { status = removedCountText(total) } else { bulkFailure = BulkFailure(mode: .deleteFiles, items: failed, removed: total - failed.count) }
            await syncLibrary()
        }
    }

    func retryBulkRemoval() {
        guard let failure = bulkFailure else { return }
        bulkFailure = nil
        Task {
            let permanently = failure.mode == .deleteFiles
            bulkProgress = L("library_bulk_removing", 0, failure.items.count)
            let failed = await telegram.removeTitlesRemotely(failure.items, permanently: permanently) { [weak self] done in
                self?.bulkProgress = L("library_bulk_removing", done, failure.items.count)
            }
            for item in failure.items where !failed.contains(where: { $0.id == item.id }) { store.removeFromLibrary(item.id) }
            refresh()
            bulkProgress = nil
            if permanently { telegram.processDeletions() }
            if !failed.isEmpty { bulkFailure = BulkFailure(mode: failure.mode, items: failed, removed: failure.removed + failure.items.count - failed.count) }
            await syncLibrary()
        }
    }

    func dismissBulkFailure() { bulkFailure = nil }
}

/// "5 titles removed" from the plural entries of the catalog.
func removedCountText(_ count: Int) -> String {
    String.localizedStringWithFormat(NSLocalizedString("library_removed_count", comment: ""), count)
}
