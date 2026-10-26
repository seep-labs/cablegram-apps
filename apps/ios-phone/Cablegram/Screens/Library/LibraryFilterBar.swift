import CablegramCore
import SwiftUI

private extension LibrarySort {
    var labelKey: String {
        switch self {
        case .recentlyAdded: "ui_recently_added_96ec5d"
        case .lastWatched: "library_sort_last_watched"
        case .titleAZ: "library_sort_title"
        case .year: "library_sort_year"
        case .sizeLargest: "library_sort_size"
        }
    }
}

private extension LibraryLocation {
    var labelKey: String {
        switch self {
        case .onPhone: "library_where_phone"
        case .cloud: "library_where_cloud"
        case .telegram: "library_where_telegram"
        case .unavailable: "library_where_unavailable"
        }
    }
}

private extension WatchedFilter {
    var labelKey: String {
        switch self {
        case .any: "library_watched_any"
        case .unwatched: "library_filter_unwatched"
        case .started: "library_watched_started"
        case .watched: "library_watched_done"
        }
    }
}

extension LibraryQuery {
    /// What "Clear all" resets: all filters, but not the sort.
    func cleared() -> LibraryQuery { LibraryQuery(sort: sort) }
}

/// "12 titles" / "1 title", from the plural entries of the catalog.
func libraryTitleCount(_ count: Int) -> String {
    String.localizedStringWithFormat(NSLocalizedString("library_title_count", comment: ""), count)
}

/// Type chips, the More sheet, removable pills for the filters hidden in it, the title count and the sort menu. It is the
/// pinned section header of the library list, so it stays at the top once scrolled past.
struct LibraryFilterBar: View {
    let query: LibraryQuery
    let count: Int
    let collections: [UserCollection]
    let onChange: (LibraryQuery) -> Void
    let onOpenMore: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach([(LibraryTypeFilter.all, "all", "ui_all_a52ace"), (.movies, "movies", "ui_movies_dff924"), (.shows, "shows", "ui_tv_shows_222906")], id: \.1) { type, key, label in
                        FilterChip(label: label, selected: query.type == type) { var next = query; next.type = type; onChange(next) }
                            .accessibilityIdentifier("library_filter_\(key)")
                    }
                    ForEach([(WatchedFilter.unwatched, "unwatched", "library_filter_unwatched"), (.started, "in_progress", "library_filter_in_progress")], id: \.1) { watched, key, label in
                        FilterChip(label: label, selected: query.watched == watched) {
                            var next = query; next.watched = query.watched == watched ? .any : watched; onChange(next)
                        }
                        .accessibilityIdentifier("library_filter_\(key)")
                    }
                    let more = query.moreFilterCount
                    Button(action: onOpenMore) {
                        Text(more > 0 ? L("library_filter_more_active", more) : L("library_filter_more")).font(PhoneFont.labelLarge)
                            .foregroundStyle(more > 0 ? Color.phoneSuccess : .white)
                            .padding(.horizontal, 14).frame(minHeight: 32)
                            .background(more > 0 ? Color.phoneSuccessContainer : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(more > 0 ? Color.clear : Color.phoneOutline, lineWidth: 1))
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("library_filter_more")
                }
            }
            HStack(spacing: 8) {
                Text(libraryTitleCount(count)).font(PhoneFont.labelLarge).foregroundStyle(Color.phoneMuted).accessibilityIdentifier("library_count")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(query.locations.sorted { $0.rawValue < $1.rawValue }, id: \.self) { location in
                            pill(L(location.labelKey)) { var next = query; next.locations.remove(location); onChange(next) }
                        }
                        if query.watched == .watched {
                            pill(L("library_watched_done")) { var next = query; next.watched = .any; onChange(next) }
                        }
                        ForEach(collections.filter { query.collectionIds.contains($0.id) }) { collection in
                            pill(collection.name) { var next = query; next.collectionIds.remove(collection.id); onChange(next) }
                        }
                    }
                }
                Menu {
                    Picker(L("library_sort_button", ""), selection: Binding(get: { query.sort }, set: { var next = query; next.sort = $0; onChange(next) })) {
                        ForEach(LibrarySort.allCases, id: \.self) { sort in Text(L(sort.labelKey)).tag(sort) }
                    }
                } label: {
                    Label(L("library_sort_button", L(query.sort.labelKey)), systemImage: "arrow.up.arrow.down").font(PhoneFont.labelMedium)
                        .foregroundStyle(Color.phoneAccent).lineLimit(1)
                }
                .accessibilityIdentifier("library_sort")
            }
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.phoneBackground)
    }

    private func pill(_ label: String, remove: @escaping () -> Void) -> some View {
        Button(action: remove) {
            HStack(spacing: 4) {
                Text(label).font(PhoneFont.labelMedium).lineLimit(1)
                Image(systemName: "xmark").font(.system(.caption2))
            }
            .foregroundStyle(Color.phoneSuccess)
            .padding(.horizontal, 10).frame(minHeight: 28)
            .background(Color.phoneSuccessContainer, in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("library_filter_remove", label))
    }
}

/// Location, watched state and collection filters, applied as they are tapped; "Show N" just closes the sheet.
struct LibraryFilterSheet: View {
    let query: LibraryQuery
    let count: Int
    let collections: [UserCollection]
    let onChange: (LibraryQuery) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    heading("library_where_heading")
                    ForEach(LibraryLocation.allCases, id: \.self) { location in
                        checkRow(L(location.labelKey), checked: query.locations.contains(location), id: "library_where_\(location.rawValue)") { on in
                            var next = query
                            if on { next.locations.insert(location) } else { next.locations.remove(location) }
                            onChange(next)
                        }
                    }
                    heading("library_progress_heading").padding(.top, 12)
                    ForEach(WatchedFilter.allCases, id: \.self) { watched in
                        Button { var next = query; next.watched = watched; onChange(next) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: query.watched == watched ? "largecircle.fill.circle" : "circle")
                                    .foregroundStyle(query.watched == watched ? Color.phoneAccent : Color.phoneMuted)
                                Text(L(watched.labelKey)).font(PhoneFont.bodyLarge).foregroundStyle(.white)
                                Spacer()
                            }
                            .frame(minHeight: 44).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(query.watched == watched ? .isSelected : [])
                        .accessibilityIdentifier("library_watched_\(watched.rawValue)")
                    }
                    if !collections.isEmpty {
                        heading("library_collection_heading").padding(.top, 12)
                        ForEach(collections) { collection in
                            checkRow(collection.name, checked: query.collectionIds.contains(collection.id), id: "library_collection_\(collection.id)") { on in
                                var next = query
                                if on { next.collectionIds.insert(collection.id) } else { next.collectionIds.remove(collection.id) }
                                onChange(next)
                            }
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 20)
            }
            HStack(spacing: 12) {
                Button { onChange(query.cleared()) } label: { Text(key: "library_filter_clear_all").frame(maxWidth: .infinity) }
                    .buttonStyle(OutlinedButtonStyle()).accessibilityIdentifier("library_filter_clear")
                Button { dismiss() } label: { Text(L("library_filter_show", count)).frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle(minHeight: 48)).accessibilityIdentifier("library_filter_show")
            }
            .padding(20)
        }
        .background(Color.phonePanel.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func heading(_ key: String) -> some View {
        Text(key: key).font(PhoneFont.labelLarge).foregroundStyle(Color.phoneMuted).accessibilityAddTraits(.isHeader)
    }

    private func checkRow(_ label: String, checked: Bool, id: String, onChange: @escaping (Bool) -> Void) -> some View {
        Button { onChange(!checked) } label: {
            HStack(spacing: 12) {
                Image(systemName: checked ? "checkmark.square.fill" : "square").foregroundStyle(checked ? Color.phoneAccent : Color.phoneMuted)
                Text(label).font(PhoneFont.bodyLarge).foregroundStyle(.white)
                Spacer()
            }
            .frame(minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}
