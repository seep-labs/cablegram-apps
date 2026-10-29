import { describe, expect, it } from "vitest";
import { parseTitle } from "../src/api";
import { initialHud, reduceHud } from "../src/hud";
import type { HudContext } from "../src/hud";
import {
  allTitles, buildShelves, displayTitle, formatRuntime, metadataParts, monogram, placeholderColors, privateThumbnail, remainingLabel, showTitle,
  sourceBadge, toEntries,
} from "../src/library";

const t = (raw: Record<string, unknown>) => parseTitle({ media_type: "movie", ...raw });

describe("artwork matches the Android TV app", () => {
  it("buckets placeholder colours like Theme.kt", () => {
    // Expected buckets computed with Kotlin's String.hashCode mixing: Sintel 4, Big Buck Bunny 3, Mason 6.
    expect(placeholderColors("Sintel")[0]).toBe("#6b6a2e");
    expect(placeholderColors("Big Buck Bunny")[0]).toBe("#3b4f8a");
    expect(placeholderColors("Mason")[0]).toBe("#4a3f7a");
  });
  it("picks private stand-ins with floorMod", () => {
    expect(privateThumbnail("Sintel")).toBe("img/private_thumb_plum.webp");
  });
  it("draws monograms from the first two words", () => {
    expect(monogram("Big Buck Bunny")).toBe("BB");
    expect(monogram("Sintel")).toBe("SI");
    expect(monogram("--")).toBe("C");
  });
});

describe("labels", () => {
  it("hides internal file names and formats episodes", () => {
    expect(showTitle(t({ id: "1", title: "Movie.2019.1080p.WEBRip" }))).toBe("Personal video");
    expect(displayTitle(t({ id: "1", title: "Silo", media_type: "tv", season_number: 1, episode_number: 9 }))).toBe("Silo · S01E09");
  });
  it("formats runtime and time left", () => {
    expect(formatRuntime(6720)).toBe("1h 52m");
    expect(formatRuntime(10)).toBe("1m");
    expect(remainingLabel(t({ id: "1", duration_seconds: 3600, progress: { position_seconds: 600, state: "paused" } }))).toBe("50m left");
  });
  it("lists metadata and sources in Android's order", () => {
    expect(metadataParts(t({ id: "1", year: 2010, duration_seconds: 888, genres: ["A", "B", "C"] }))).toEqual(["2010", "15m", "A, B"]);
    expect(sourceBadge(["phone_local", "telegram", "own_cloud"])).toBe("Telegram · Cloud · Phone");
  });
  it("never shows a private title's artwork", () => {
    const v = t({ id: "1", poster_url: "p", sources: [{ kind: "phone_local", private: true }] });
    expect(v.posterUrl).toBeUndefined();
    expect(v.isPrivate).toBe(true);
  });
});

describe("shelves", () => {
  const videos = [
    t({ id: "m1", title: "Old", created_at: "2026-01-01", genres: ["Drama"], in_my_list: true }),
    t({ id: "m2", title: "New", created_at: "2026-02-01", genres: ["Comedy"], progress: { position_seconds: 5, state: "paused" } }),
    t({ id: "e1", title: "Show", media_type: "tv", tmdb_id: 7, season_number: 1, episode_number: 2, created_at: "2026-03-01" }),
    t({ id: "e2", title: "Show", media_type: "tv", tmdb_id: 7, season_number: 1, episode_number: 1, created_at: "2026-03-02" }),
  ];
  it("groups episodes into one show, sorted by episode", () => {
    const show = toEntries(videos).filter((e) => e.kind === "show")[0];
    expect(show.kind === "show" && show.show.episodes.map((e) => e.id)).toEqual(["e2", "e1"]);
  });
  it("builds Continue, Recently added, My List, then genres", () => {
    expect(buildShelves(videos, "all", null).map((s) => s.id)).toEqual(["continue", "recent", "mylist", "genre:Comedy", "genre:Drama"]);
    expect(buildShelves(videos, "all", null)[1].entries.map((e) => e.id)).toEqual(["tmdb:7", "m2", "m1"]);
  });
  it("keeps Continue and My List as single lists", () => {
    expect(buildShelves(videos, "watchlist", null).map((s) => s.id)).toEqual(["continue"]);
    expect(buildShelves(videos, "mylist", null)[0].entries.map((e) => e.id)).toEqual(["m1"]);
  });
});

describe("player remote", () => {
  const ctx: HudContext = { isPlaying: true, positionMs: 60_000, durationMs: 600_000, audioTracks: [], subtitleTracks: [],
    selectedAudioId: null, selectedSubtitleId: null, speed: 1, aspect: "Fit" };
  it("jumps on a first press and skims on the next", () => {
    const [first, effects] = reduceHud(initialHud, "right", 0, ctx);
    expect(effects).toEqual([{ type: "seekBy", deltaMs: 10_000 }]);
    const [second] = reduceHud(first, "right", 0, ctx);
    expect(second.focus).toBe("timeline");
    expect(second.previewPositionMs).toBe(70_000);
  });
  it("OK pauses and shows the controls; Back exits only when they are hidden", () => {
    const [shown, effects] = reduceHud(initialHud, "ok", 0, ctx);
    expect(effects).toEqual([{ type: "pause" }]);
    expect(shown.controlsVisible).toBe(true);
    expect(reduceHud(shown, "back", 0, ctx)[0].controlsVisible).toBe(false);
    expect(reduceHud(initialHud, "back", 0, ctx)[1]).toEqual([{ type: "exit" }]);
  });
  it("lists a track the TV cannot decode but does not switch to it", () => {
    const audio = { ...ctx, audioTracks: [{ id: "emb:2", label: "AAC" }, { id: "emb:3", label: "Persian · MP3 (not supported on this TV)", disabled: true }], selectedAudioId: "emb:2" };
    const menu = { ...initialHud, controlsVisible: true, overlay: "audioSubtitles" as const, menuSection: "audio" as const, menuIndex: 0 };
    const [onMp3] = reduceHud(menu, "down", 0, audio);
    expect(onMp3.menuIndex).toBe(1);
    expect(reduceHud(onMp3, "ok", 0, audio)[1]).toEqual([]);
    expect(reduceHud(menu, "ok", 0, audio)[1]).toEqual([{ type: "audio", id: "emb:2" }]);
  });
  it("Down past the last audio track enters the subtitles, and Up returns to the last audio track", () => {
    const both = { ...ctx, audioTracks: [{ id: "a1", label: "English" }, { id: "a2", label: "Persian" }], subtitleTracks: [{ id: "s1", label: "English" }], selectedAudioId: "a1" };
    const lastAudio = { ...initialHud, controlsVisible: true, overlay: "audioSubtitles" as const, menuSection: "audio" as const, menuIndex: 1 };
    const [inSubtitles] = reduceHud(lastAudio, "down", 0, both);
    expect([inSubtitles.menuSection, inSubtitles.menuIndex]).toEqual(["subtitles", 0]); // "Off"
    const [second] = reduceHud(inSubtitles, "down", 0, both);
    expect(second.menuIndex).toBe(1);
    expect(reduceHud(second, "down", 0, both)[0].menuIndex).toBe(1); // the end of the subtitles stays put
    const [backToOff] = reduceHud(second, "up", 0, both);
    expect([backToOff.menuSection, backToOff.menuIndex]).toEqual(["subtitles", 0]);
    const [backToAudio] = reduceHud(backToOff, "up", 0, both);
    expect([backToAudio.menuSection, backToAudio.menuIndex]).toEqual(["audio", 1]);
    expect(reduceHud({ ...lastAudio, menuIndex: 0 }, "up", 0, both)[0].menuIndex).toBe(0); // the top of the audio stays put
  });
  it("with no audio tracks Down goes straight to the subtitles, and Up from them stays", () => {
    const noAudio = { ...ctx, audioTracks: [], subtitleTracks: [{ id: "s1", label: "English" }] };
    const menu = { ...initialHud, controlsVisible: true, overlay: "audioSubtitles" as const, menuSection: "audio" as const, menuIndex: 0 };
    const [inSubtitles] = reduceHud(menu, "down", 0, noAudio);
    expect([inSubtitles.menuSection, inSubtitles.menuIndex]).toEqual(["subtitles", 0]);
    expect(reduceHud(inSubtitles, "up", 0, noAudio)[0].menuSection).toBe("subtitles");
  });
  it("Left and Right still jump between the columns", () => {
    const both = { ...ctx, audioTracks: [{ id: "a1", label: "English" }], subtitleTracks: [{ id: "s1", label: "English" }], selectedAudioId: "a1" };
    const menu = { ...initialHud, controlsVisible: true, overlay: "audioSubtitles" as const, menuSection: "audio" as const, menuIndex: 0 };
    expect(reduceHud(menu, "right", 0, both)[0].menuSection).toBe("subtitles");
    expect(reduceHud(reduceHud(menu, "right", 0, both)[0], "left", 0, both)[0].menuSection).toBe("audio");
  });
});

describe("collections and all titles", () => {
  const kids = { id: "c-kids", name: "Kids" };
  const fav = { id: "c-fav", name: "favourites" };
  const videos = [
    t({ id: "up", title: "Up", created_at: "2026-09-08", genres: ["Animation"], year: 2009, collections: [kids] }),
    t({ id: "cars", title: "Cars", created_at: "2026-09-09", genres: ["Animation"], year: 2006, collections: [kids, fav] }),
    t({ id: "heat", title: "Heat", created_at: "2026-09-01", genres: ["Action"], year: 1995, in_my_list: true }),
    t({ id: "zodiac", title: "zodiac", created_at: "2026-09-02" }),
    t({ id: "bluey1", title: "Bluey", media_type: "tv", tmdb_id: 5, season_number: 1, episode_number: 1, created_at: "2026-09-05", year: 2018, collections: [kids] }),
    t({ id: "bluey2", title: "Bluey", media_type: "tv", tmdb_id: 5, season_number: 2, episode_number: 1, created_at: "2026-09-06", year: 2020, collections: [kids] }),
  ];

  it("reads each title's collections from the catalog and ignores malformed ones", () => {
    expect(t({ id: "1", collections: [{ id: "c1", name: "Kids" }, { id: 3 }, null] }).collections).toEqual([{ id: "c1", name: "Kids" }]);
    expect(t({ id: "1" }).collections).toEqual([]);
  });

  it("puts each collection after My List and before the genres, by name, newest first", () => {
    const shelves = buildShelves(videos, "all", null);
    expect(shelves.map((s) => s.id)).toEqual(["recent", "mylist", "collection:c-fav", "collection:c-kids", "genre:Action", "genre:Animation"]);
    expect(shelves.filter((s) => s.id.startsWith("collection:")).map((s) => s.title)).toEqual(["favourites", "Kids"]);
    expect(shelves.find((s) => s.id === "collection:c-kids")!.entries.map((e) => e.id)).toEqual(["cars", "up", "tmdb:5"]);
  });

  it("follows the type filter and leaves collections out when a genre is chosen", () => {
    expect(buildShelves(videos, "movie", null).find((s) => s.id === "collection:c-kids")!.entries.map((e) => e.id)).toEqual(["cars", "up"]);
    expect(buildShelves(videos, "tv", null).find((s) => s.id === "collection:c-kids")!.entries.map((e) => e.id)).toEqual(["tmdb:5"]);
    expect(buildShelves(videos, "all", "Animation").some((s) => s.id.startsWith("collection:"))).toBe(false);
  });

  it("lists every title once, a show as one poster, newest first by default", () => {
    expect(allTitles(videos, "all", "recent").map((e) => e.id)).toEqual(["cars", "up", "tmdb:5", "zodiac", "heat"]);
  });

  it("sorts by title ignoring case, and by year newest first with unknown years last", () => {
    expect(allTitles(videos, "all", "title").map((e) => e.id)).toEqual(["tmdb:5", "cars", "heat", "up", "zodiac"]);
    // A show counts as its earliest episode year (2018).
    expect(allTitles(videos, "all", "year").map((e) => e.id)).toEqual(["tmdb:5", "up", "cars", "heat", "zodiac"]);
  });

  it("narrows to movies or shows and is empty for an empty library", () => {
    expect(allTitles(videos, "movie", "recent").map((e) => e.id)).toEqual(["cars", "up", "zodiac", "heat"]);
    expect(allTitles(videos, "tv", "recent").map((e) => e.id)).toEqual(["tmdb:5"]);
    expect(allTitles([], "all", "recent")).toEqual([]);
  });
});
