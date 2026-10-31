import { describe, expect, it } from "vitest";
import { backoffDelay, parseChange, startLive } from "../src/live";
import type { LiveChange, SocketLike } from "../src/live";
import { createApi } from "../src/api";

class FakeSocket implements SocketLike {
  onopen: SocketLike["onopen"] = null; onmessage: SocketLike["onmessage"] = null;
  onclose: SocketLike["onclose"] = null; onerror: SocketLike["onerror"] = null;
  closed = false;
  constructor(readonly url: string) {}
  close() { this.closed = true; }
  say(data: unknown) { this.onmessage?.({ data: typeof data === "string" ? data : JSON.stringify(data) }); }
  drop() { this.onclose?.({}); }
}

/** A manual clock: timers fire only when the test advances time. */
function harness(over: { ticket?: () => Promise<string> } = {}) {
  let now = 0; let nextId = 1;
  const timers = new Map<number, { at: number; fn: () => void }>();
  const sockets: FakeSocket[] = [];
  const changes: LiveChange[] = [];
  const states: boolean[] = [];
  let tickets = 0;
  const live = startLive({
    ticket: over.ticket ?? (async () => `t${++tickets}`),
    socketUrl: (t) => `wss://x/socket?ticket=${t}`,
    onChanged: (c) => changes.push(c),
    onState: (u) => states.push(u),
    createSocket: (url) => { const s = new FakeSocket(url); sockets.push(s); return s; },
    random: () => 0.5, // no jitter: exactly the base delay
    setTimer: (fn, ms) => { const id = nextId++; timers.set(id, { at: now + ms, fn }); return id; },
    clearTimer: (id) => { timers.delete(id); },
  });
  const flush = () => new Promise<void>((r) => setTimeout(r, 0));
  async function advance(ms: number) {
    const end = now + ms;
    for (;;) {
      const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      now = due[1].at; timers.delete(due[0]); due[1].fn(); await flush();
    }
    now = end;
  }
  return { live, sockets, changes, states, advance, flush, get tickets() { return tickets; }, pending: () => timers.size };
}

describe("backoffDelay", () => {
  it("doubles from 1 s to a 30 s ceiling", () => {
    const d = [0, 1, 2, 3, 4, 5, 6, 10].map((n) => backoffDelay(n, () => 0.5));
    expect(d).toEqual([1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000]);
  });
  it("spreads by ±30%", () => {
    expect(backoffDelay(2, () => 0)).toBe(2_800);
    expect(backoffDelay(2, () => 1)).toBe(5_200);
  });
});

describe("parseChange", () => {
  it("reads known scopes and ignores everything else", () => {
    expect(parseChange('{"type":"changed","scope":"catalog","profileId":"p"}')).toEqual({ scope: "catalog", profileId: "p" });
    expect(parseChange('{"type":"changed","scope":"profiles"}')).toEqual({ scope: "profiles" });
    expect(parseChange('{"type":"changed","scope":"somethingNew"}')).toBeNull();
    expect(parseChange('{"type":"command"}')).toBeNull();
    expect(parseChange("not json")).toBeNull();
    expect(parseChange(42)).toBeNull();
  });
});

describe("startLive", () => {
  it("connects with a fresh ticket, reports up on ready, and delivers a debounced change", async () => {
    const h = harness();
    await h.flush();
    expect(h.sockets.map((s) => s.url)).toEqual(["wss://x/socket?ticket=t1"]);
    h.sockets[0].say({ type: "ready" });
    expect(h.states).toEqual([true]);
    h.sockets[0].say({ type: "changed", scope: "catalog" });
    h.sockets[0].say({ type: "changed", scope: "catalog" });
    h.sockets[0].say({ type: "mystery" });
    h.sockets[0].say("garbage");
    expect(h.changes).toHaveLength(0);
    await h.advance(300);
    expect(h.changes).toEqual([{ scope: "catalog" }]);
    expect(h.sockets[0].closed).toBe(false);
  });

  it("reconnects with a new ticket and growing delays while it keeps failing", async () => {
    const h = harness();
    await h.flush();
    h.sockets[0].drop();
    await h.advance(999); expect(h.sockets).toHaveLength(1);
    await h.advance(1); expect(h.sockets).toHaveLength(2);
    h.sockets[1].drop();
    await h.advance(1_999); expect(h.sockets).toHaveLength(2);
    await h.advance(1); expect(h.sockets).toHaveLength(3);
    expect(h.sockets.map((s) => s.url.split("ticket=")[1])).toEqual(["t1", "t2", "t3"]);
  });

  it("starts over at 1 s after a connection held for 10 s", async () => {
    const h = harness();
    await h.flush();
    h.sockets[0].drop(); await h.advance(1_000);
    h.sockets[1].drop(); await h.advance(2_000);
    h.sockets[2].say({ type: "ready" });
    await h.advance(10_000);
    h.sockets[2].drop();
    await h.advance(1_000);
    expect(h.sockets).toHaveLength(4);
    expect(h.states).toEqual([true, false]);
  });

  it("retries when the ticket request fails, but gives up on 401", async () => {
    let calls = 0;
    const h = harness({ ticket: async () => { calls++; throw Object.assign(new Error("net"), { status: 0 }); } });
    await h.flush(); expect(calls).toBe(1);
    await h.advance(1_000); expect(calls).toBe(2);
    const dead = harness({ ticket: async () => { throw Object.assign(new Error("no"), { status: 401 }); } });
    await dead.flush(); await dead.advance(60_000);
    expect(dead.sockets).toHaveLength(0);
    expect(dead.pending()).toBe(0);
  });

  it("nudge reconnects at once; a forced nudge replaces a socket that looks up", async () => {
    const h = harness();
    await h.flush();
    h.sockets[0].drop();
    h.live.nudge();
    await h.flush();
    expect(h.sockets).toHaveLength(2);
    h.sockets[1].say({ type: "ready" });
    h.live.nudge(); await h.flush();
    expect(h.sockets).toHaveLength(2);
    h.live.nudge(true); await h.flush();
    expect(h.sockets).toHaveLength(3);
    expect(h.sockets[1].closed).toBe(true);
  });

  it("stop closes the socket, cancels timers and ignores late messages", async () => {
    const h = harness();
    await h.flush();
    const s = h.sockets[0];
    s.say({ type: "ready" });
    s.say({ type: "changed", scope: "catalog" });
    h.live.stop();
    expect(s.closed).toBe(true);
    s.say({ type: "changed", scope: "profiles" });
    await h.advance(60_000);
    expect(h.changes).toHaveLength(0);
    expect(h.sockets).toHaveLength(1);
    expect(h.pending()).toBe(0);
  });
});

describe("socket ticket api", () => {
  it("posts with the TV's token and builds a ws url", async () => {
    let seen: { url: string; auth?: string; method?: string } | null = null;
    const api = createApi({ base: "https://api.example/", fetch: async (url, init) => {
      seen = { url, auth: (init?.headers as Record<string, string>).authorization, method: init?.method };
      return new Response(JSON.stringify({ ticket: "abc", expires_in_ms: 30000 }), { status: 200 });
    } });
    expect(await api.socketTicket("tok")).toBe("abc");
    expect(seen).toEqual({ url: "https://api.example/api/control/commands/socket-ticket", auth: "Bearer tok", method: "POST" });
    expect(api.socketUrl("a b")).toBe("wss://api.example/api/control/commands/socket?ticket=a%20b");
  });
});
