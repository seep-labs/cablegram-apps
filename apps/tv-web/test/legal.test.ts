import { describe, expect, it } from "vitest";
import { loadNotice, nextScrollTop, NOTICE_MISSING, NOTICE_PATH } from "../src/legal";

describe("licence and notices", () => {
  it("reads legal/NOTICE", async () => {
    let asked = "";
    const text = await loadNotice(async (p) => { asked = p; return "Cablegram\nGPL-3.0"; });
    expect(asked).toBe(NOTICE_PATH);
    expect(text).toBe("Cablegram\nGPL-3.0");
  });
  it("says so when the file is missing, unreadable or empty", async () => {
    expect(await loadNotice(async () => { throw new Error("HTTP 404"); })).toBe(NOTICE_MISSING);
    expect(await loadNotice(async () => "  \n")).toBe(NOTICE_MISSING);
  });
  it("scrolls a page at a time and stays inside the text", () => {
    expect(nextScrollTop(0, "down", 500, 2000)).toBe(400);
    expect(nextScrollTop(400, "up", 500, 2000)).toBe(0);
    expect(nextScrollTop(0, "up", 500, 2000)).toBe(0);
    expect(nextScrollTop(1400, "down", 500, 2000)).toBe(1500);
    expect(nextScrollTop(1500, "down", 500, 2000)).toBe(1500);
  });
  it("does not move for a text that fits, or for other keys", () => {
    expect(nextScrollTop(0, "down", 500, 300)).toBe(0);
    expect(nextScrollTop(120, "left", 500, 2000)).toBe(120);
  });
});
