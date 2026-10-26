import { describe, expect, it, vi } from "vitest";
import { installAbortPolyfill } from "../src/abort-polyfill";

describe("installAbortPolyfill", () => {
  it("leaves a browser that has AbortController alone", () => {
    const fetch = vi.fn();
    const target: Record<string, unknown> = { AbortController: class {}, fetch };
    expect(installAbortPolyfill(target)).toBe(false);
    expect(target.fetch).toBe(fetch);
  });

  it("gives an old browser a working controller (Chromium 63, Tizen 5.0)", () => {
    const target: Record<string, unknown> = {};
    expect(installAbortPolyfill(target)).toBe(true);
    const ctl = new (target.AbortController as typeof AbortController)();
    const heard: string[] = [];
    const fn = () => heard.push("listener");
    ctl.signal.onabort = () => heard.push("onabort");
    ctl.signal.addEventListener("abort", fn);
    ctl.signal.addEventListener("abort", () => heard.push("removed"));
    expect(ctl.signal.aborted).toBe(false);
    ctl.abort();
    ctl.abort();
    expect(ctl.signal.aborted).toBe(true);
    expect((ctl.signal.reason as Error).name).toBe("AbortError");
    expect(heard).toEqual(["onabort", "listener", "removed"]);
  });

  it("keeps the native fetch for requests without a signal", async () => {
    const native = vi.fn(async () => new Response("ok"));
    const target: Record<string, unknown> = { fetch: native };
    installAbortPolyfill(target);
    await (target.fetch as typeof fetch)("https://example.test/a", { headers: { a: "1" } });
    expect(native).toHaveBeenCalledWith("https://example.test/a", { headers: { a: "1" } });
  });
});
