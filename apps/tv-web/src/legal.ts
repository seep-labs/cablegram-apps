import type { Key } from "./nav";

/** Where the build puts the repository's NOTICE (vite.config.ts), as in the Android TV app's `assets/legal/NOTICE`. */
export const NOTICE_PATH = "legal/NOTICE";
export const NOTICE_MISSING = "NOTICE is missing from this build.";

/** The notice text, or a one-line stand-in when the file cannot be read. [read] is XHR-based in the app (see diagnostics.ts). */
export async function loadNotice(read: (path: string) => Promise<string>): Promise<string> {
  try {
    const text = await read(NOTICE_PATH);
    return text.trim() ? text : NOTICE_MISSING;
  } catch {
    return NOTICE_MISSING;
  }
}

/** Reads a bundled text file. XHR rather than fetch: fetch cannot read file:// in the packaged TV apps. */
export function readText(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open("GET", path);
    x.onload = () => ((x.status === 0 || (x.status >= 200 && x.status < 300)) ? resolve(x.responseText) : reject(new Error(`HTTP ${x.status}`)));
    x.onerror = () => reject(new Error("could not read the file"));
    x.send();
  });
}

/**
 * The scroll position after Up or Down on the notice: a page-ish step (a fifth short of the view, so a line is never lost),
 * clamped to the text. Pure, so it is testable.
 */
export function nextScrollTop(top: number, key: Key, viewHeight: number, contentHeight: number): number {
  const step = Math.max(1, Math.round(viewHeight * 0.8));
  const max = Math.max(0, contentHeight - viewHeight);
  const next = key === "down" ? top + step : key === "up" ? top - step : top;
  return Math.min(max, Math.max(0, next));
}
