import { describe, expect, it } from "vitest";
import { DENIED_LABEL, createApi, parseServerCapabilities, parseTitle, relayUrl, servingPhone } from "../src/api";
import { pickNext } from "../src/nav";
import { normalizeServerUrl, setServerUrl, serverUrl } from "../src/session";

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

describe("parseTitle", () => {
  it("hides resume for completed titles and reads sources", () => {
    const t = parseTitle({ id: "1", title: "A", progress: { state: "completed", position_seconds: 50 },
      sources: [{ kind: "phone_local", origin_identity: "x", serving_device_id: "p1" }] });
    expect(t.resumeSeconds).toBeUndefined();
    expect(t.sources[0].originIdentity).toBe("x");
  });
});

describe("relayUrl", () => {
  it("carries the capability and ticket as query parameters", () => {
    const u = new URL(relayUrl("https://api.example/", "ph 1", "media/abc", "cap", "tick"));
    expect(u.pathname).toBe("/relay/v1/p/ph%201/media/abc");
    expect(u.searchParams.get("token")).toBe("cap");
    expect(u.searchParams.get("rt")).toBe("tick");
  });
});

describe("servingPhone", () => {
  it("prefers the serving device and skips revoked phones", () => {
    const d = [{ id: "a", kind: "phone", revokedAt: "x" }, { id: "b", kind: "phone" }, { id: "c", kind: "phone" }];
    expect(servingPhone(d, "c")?.id).toBe("c");
    expect(servingPhone(d, "a")?.id).toBe("b");
  });
});

describe("resolvePlayback", () => {
  const phoneTitle = parseTitle({ id: "1", title: "A", sources: [{ kind: "phone_local", origin_identity: "x", serving_device_id: "p1" }] });
  it("plays a phone copy through the relay", async () => {
    const api = createApi({ base: "https://api.example", token: () => "t", fetch: async (url) =>
      url.endsWith("api/me") ? json(200, { devices: [{ id: "p1", kind: "phone" }] }) : json(200, { ticket: "rt1" }) });
    const p = await api.resolvePlayback(phoneTitle, "cap");
    expect(p.status).toBe("ready");
    expect(p.url).toContain("/relay/v1/p/p1/media/x");
  });
  it("refuses Telegram-only titles with an explanation", async () => {
    const api = createApi({ fetch: async () => json(200, {}) });
    const tg = parseTitle({ id: "3", sources: [{ kind: "telegram", origin_identity: "tg:1:2" }] });
    expect((await api.resolvePlayback(tg, undefined)).message).toMatch(/Telegram/);
  });
  it("plays a private title after phone approval and carries the lan pass", async () => {
    let awaitingApprovalCalled = false;
    let statusMessage = "";
    const priv = parseTitle({
      id: "priv1",
      title: "Secret",
      sources: [{ kind: "phone_local", origin_identity: "file-p", private: true, serving_device_id: "ph1" }],
    });
    const calls: string[] = [];
    const api = createApi({
      base: "https://api.example",
      token: () => "tv-token",
      fetch: async (url, init) => {
        calls.push(`${init?.method ?? "GET"} ${url}`);
        if (url.endsWith("api/playback/private-approvals") && init?.method === "POST") {
          return json(201, { attempt_id: "att-1", expires_at: "2026-10-26T18:00:00Z" });
        }
        if (url.endsWith("api/playback/private-approvals/att-1")) {
          return json(200, { attempt_id: "att-1", status: "approved" });
        }
        if (url.endsWith("api/playback/private-approvals/att-1/start") && init?.method === "POST") {
          return json(200, { lan_pass: "pass-123", expires_at: "2026-10-26T19:00:00Z" });
        }
        if (url.endsWith("api/me")) {
          return json(200, { devices: [{ id: "ph1", kind: "phone" }] });
        }
        if (url.endsWith("api/relay/ticket")) {
          return json(200, { ticket: "rt1" });
        }
        return json(404, {});
      },
    });

    const p = await api.resolvePlayback(priv, "cap", {
      pollIntervalMs: 5,
      onAwaitingApproval: () => { awaitingApprovalCalled = true; },
      onStatus: (s) => { statusMessage = s; },
    });

    expect(awaitingApprovalCalled).toBe(true);
    expect(statusMessage).toContain("Waiting for your phone to approve");
    expect(p.status).toBe("ready");
    expect(p.url).toBe("https://api.example/relay/v1/p/ph1/media/file-p?token=cap&pass=pass-123&rt=rt1");
    expect(calls).toContain("POST https://api.example/api/playback/private-approvals");
    expect(calls).toContain("GET https://api.example/api/playback/private-approvals/att-1");
    expect(calls).toContain("POST https://api.example/api/playback/private-approvals/att-1/start");
  });
  it("returns denied status when the phone denies approval", async () => {
    const priv = parseTitle({
      id: "priv2",
      sources: [{ kind: "phone_local", origin_identity: "file-p", private: true, serving_device_id: "ph1" }],
    });
    const api = createApi({
      base: "https://api.example",
      token: () => "tv-token",
      fetch: async (url, init) => {
        if (url.endsWith("api/playback/private-approvals") && init?.method === "POST") {
          return json(201, { attempt_id: "att-2", expires_at: "2026-10-26T18:00:00Z" });
        }
        if (url.endsWith("api/playback/private-approvals/att-2")) {
          return json(200, { attempt_id: "att-2", status: "denied" });
        }
        return json(404, {});
      },
    });

    const p = await api.resolvePlayback(priv, "cap", { pollIntervalMs: 5 });
    expect(p.status).toBe("denied");
    expect(p.message).toBe(DENIED_LABEL);
  });
  it("cancels approval request and stops polling when signal aborts", async () => {
    const priv = parseTitle({
      id: "priv3",
      sources: [{ kind: "phone_local", origin_identity: "file-p", private: true, serving_device_id: "ph1" }],
    });
    const controller = new AbortController();
    const calls: string[] = [];
    let polls = 0;
    const api = createApi({
      base: "https://api.example",
      token: () => "tv-token",
      fetch: async (url, init) => {
        calls.push(`${init?.method ?? "GET"} ${url}`);
        if (url.endsWith("api/playback/private-approvals") && init?.method === "POST") {
          return json(201, { attempt_id: "att-3", expires_at: "2026-10-26T18:00:00Z" });
        }
        if (url.endsWith("api/playback/private-approvals/att-3")) {
          polls++;
          controller.abort();
          return json(200, { attempt_id: "att-3", status: "pending" });
        }
        if (url.endsWith("api/playback/private-approvals/att-3/cancel") && init?.method === "POST") {
          return json(200, { ok: true });
        }
        return json(404, {});
      },
    });

    const p = await api.resolvePlayback(priv, "cap", {
      signal: controller.signal,
      pollIntervalMs: 5,
    });

    expect(p.status).toBe("denied");
    expect(calls).toContain("POST https://api.example/api/playback/private-approvals/att-3/cancel");
    expect(polls).toBe(1);
  });
  it("returns timeout error and cancels when approval times out", async () => {
    const priv = parseTitle({
      id: "priv4",
      sources: [{ kind: "phone_local", origin_identity: "file-p", private: true, serving_device_id: "ph1" }],
    });
    const calls: string[] = [];
    const api = createApi({
      base: "https://api.example",
      token: () => "tv-token",
      fetch: async (url, init) => {
        calls.push(`${init?.method ?? "GET"} ${url}`);
        if (url.endsWith("api/playback/private-approvals") && init?.method === "POST") {
          return json(201, { attempt_id: "att-4", expires_at: "2026-10-26T18:00:00Z" });
        }
        if (url.endsWith("api/playback/private-approvals/att-4")) {
          return json(200, { attempt_id: "att-4", status: "pending" });
        }
        if (url.endsWith("api/playback/private-approvals/att-4/cancel") && init?.method === "POST") {
          return json(200, { ok: true });
        }
        return json(404, {});
      },
    });

    const p = await api.resolvePlayback(priv, "cap", {
      pollIntervalMs: 5,
      maxWaitSeconds: 0.02,
    });

    expect(p.status).toBe("denied");
    expect(p.message).toBe("No approval came in time. Start playback again when your phone is nearby.");
    expect(calls).toContain("POST https://api.example/api/playback/private-approvals/att-4/cancel");
  });
  it("resolves private cloud titles with attemptId upon approval", async () => {
    const privCloud = parseTitle({
      id: "priv-cloud",
      sources: [{ kind: "own_cloud", origin_identity: "c1", private: true }],
    });
    let resolveBody: Record<string, unknown> | null = null;
    const api = createApi({
      base: "https://api.example",
      token: () => "tv-token",
      fetch: async (url, init) => {
        if (url.endsWith("api/playback/private-approvals") && init?.method === "POST") {
          return json(201, { attempt_id: "att-cloud", expires_at: "2026-10-26T18:00:00Z" });
        }
        if (url.endsWith("api/playback/private-approvals/att-cloud")) {
          return json(200, { attempt_id: "att-cloud", status: "approved" });
        }
        if (url.endsWith("api/playback/resolve") && init?.method === "POST") {
          resolveBody = JSON.parse(init.body as string);
          return json(200, { status: "ready", url: "https://cloud.example/video.mp4", headers: { "X-Custom": "1" } });
        }
        return json(404, {});
      },
    });

    const p = await api.resolvePlayback(privCloud, undefined, { pollIntervalMs: 5 });
    expect(p.status).toBe("ready");
    expect(p.url).toBe("https://cloud.example/video.mp4");
    expect(p.headers).toEqual({ "X-Custom": "1" });
    expect(resolveBody).toEqual({ media_item_id: "priv-cloud", attempt_id: "att-cloud" });
  });
  it("maps a PIN failure to its status", async () => {
    const api = createApi({ fetch: async () => json(403, { error: "wrong_pin" }) });
    expect(await api.unlockProfile("p", "0000")).toBe(403);
  });
});

describe("pickNext", () => {
  const r = (l: number, t: number) => ({ left: l, top: t, right: l + 100, bottom: t + 100 });
  it("moves in the pressed direction and prefers the aligned neighbour", () => {
    const from = r(0, 0);
    expect(pickNext(from, [r(200, 0), r(200, 300)], "right")).toBe(0);
    expect(pickNext(from, [r(0, 200), r(300, 200)], "down")).toBe(0);
    expect(pickNext(from, [r(200, 0)], "left")).toBe(-1);
  });
});

import { pairLink } from "../src/pairlink";
describe("pairLink", () => {
  it("matches the Android TV link shape", () => {
    expect(pairLink("https://app.example/", "123456", "LG TV")).toBe("https://app.example/?pair=123456&tv=LG%20TV");
  });
});

describe("preflight", () => {
  it("passes video through and reads the relay's error code", async () => {
    const { preflight, relayMessage } = await import("../src/api");
    expect(await preflight("u", async () => new Response("x", { status: 206 }))).toBeNull();
    const refused = await preflight("u", async () => json(429, { error: "relay_streams_exceeded" }));
    expect(refused).toEqual({ status: 429, error: "relay_streams_exceeded" });
    expect(relayMessage(429, "relay_streams_exceeded")).toMatch(/Too many relay streams/);
    expect(await preflight("u", async () => { throw new Error("offline"); })).toEqual({ status: null });
  });
});

describe("server capabilities and runtime server URL", () => {
  it("parses Community capabilities and skips subtitle and relay requests when disabled", async () => {
    const calls: string[] = [];
    const api = createApi({
      base: "https://community.example",
      fetch: async (url) => {
        calls.push(url);
        if (url.endsWith("api/server")) {
          return json(200, {
            api_version: 1,
            edition: "community",
            auth: { mode: "device" },
            features: { subtitles: false, relay: false, artwork: false },
          });
        }
        return json(500, {});
      },
    });
    const caps = await api.server();
    expect(caps.edition).toBe("community");
    expect(caps.authMode).toBe("device");
    expect(caps.features.subtitles).toBe(false);
    expect(caps.features.relay).toBe(false);
    expect(caps.features.telegram).toBe(true);

    // Subtitles should not make an HTTP request when subtitles=false
    const subs = await api.subtitles("item-1");
    expect(subs).toEqual([]);
    expect(calls).toEqual(["https://community.example/api/server"]);

    // Phone-only title should report relay unsupported without calling /api/me or /api/relay/ticket
    const phoneTitle = parseTitle({ id: "1", title: "A", sources: [{ kind: "phone_local", origin_identity: "x", serving_device_id: "p1" }] });
    const pb = await api.resolvePlayback(phoneTitle, "cap");
    expect(pb.status).toBe("unsupported");
    expect(pb.message).toMatch(/Relay playback is not enabled/);
  });

  it("normalizes HTTPS and LAN HTTP server URLs and rejects public HTTP", () => {
    expect(normalizeServerUrl("https://tv.example.com/")).toBe("https://tv.example.com");
    expect(normalizeServerUrl("http://192.168.1.20:3000/")).toBe("http://192.168.1.20:3000");
    expect(normalizeServerUrl("http://tv.example.com")).toBeUndefined();
    expect(setServerUrl("https://self.example.com/")).toBe("https://self.example.com");
    expect(serverUrl()).toBe("https://self.example.com");
    setServerUrl(null);
    expect(serverUrl()).toBeUndefined();
  });

  it("parses default capabilities when fields are omitted", () => {
    const caps = parseServerCapabilities({});
    expect(caps.features.telegram).toBe(true);
    expect(caps.features.lanPlayback).toBe(true);
    expect(caps.features.artwork).toBe(false);
  });
});
