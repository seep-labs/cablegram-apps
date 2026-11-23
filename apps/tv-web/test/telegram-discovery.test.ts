import { describe, it } from "vitest";
import assert from "node:assert/strict";
import {
  DiscoveryController,
  DiscoveryGate,
  DiscoveryStore,
  RequestThrottle,
  filterChatsForDiscovery,
  type DiscoveryChat,
  type DiscoveryTransport,
  type KeyValueStore,
} from "../src/telegram-discovery";

function memStore(): KeyValueStore {
  const m = new Map<string, string>();
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => { m.set(k, v); },
    removeItem: (k) => { m.delete(k); },
  };
}

function fakeTransport(chats: DiscoveryChat[]): DiscoveryTransport {
  return {
    async listChats() { return chats; },
    async countChatVideos(chatId) { return { videoCount: chatId === "c1" ? 5 : 0, fileCount: 0 }; },
    async listChatVideos(chatId) {
      return [{ chatId, messageId: 10, title: "Movie", durationSeconds: 3600, dateUnix: 1700000000, protectedContent: false }];
    },
    async forwardToLibrary() {},
  };
}

describe("tv-web telegram-discovery", () => {
  it("DiscoveryGate refuses unchosen chats and the library channel", () => {
    const gate = new DiscoveryGate(() => new Set(["c1", "lib"]), () => "lib");
    assert.equal(gate.isAllowedChat("c1"), true);
    assert.equal(gate.isAllowedChat("lib"), false);
    assert.equal(gate.isAllowedChat("c2"), false);
    assert.throws(() => gate.assertAllowedChat("lib"));
    assert.throws(() => gate.assertAllowedChat("c2"));
  });

  it("excludes bots, secret chats, personal chats and Saved Messages by default", () => {
    const chats: DiscoveryChat[] = [
      { id: "c1", title: "Cinema", kind: "channel" },
      { id: "g1", title: "Friends", kind: "group" },
      { id: "p1", title: "Alice", kind: "personal" },
      { id: "s1", title: "Saved Messages", kind: "saved" },
      { id: "b1", title: "Bot", kind: "bot" },
      { id: "sec", title: "Secret", kind: "secret" },
      { id: "lib", title: "Cablegram Library", kind: "channel" },
    ];
    const def = filterChatsForDiscovery(chats, { minDurationSeconds: 600, includePersonalChats: false, includeSavedMessages: false }, "lib");
    assert.deepEqual(def.map((c) => c.id), ["c1", "g1"]);

    const withPersonal = filterChatsForDiscovery(chats, { minDurationSeconds: 600, includePersonalChats: true, includeSavedMessages: true }, "lib");
    assert.deepEqual(withPersonal.map((c) => c.id), ["c1", "g1", "p1", "s1"]);
  });

  it("RequestThrottle pauses on FLOOD_WAIT and retries", async () => {
    let t = 1000;
    const sleeps: number[] = [];
    const throttle = new RequestThrottle(20, () => t, async (ms) => { sleeps.push(ms); t += ms; });
    let attempts = 0;
    const pauses: number[] = [];
    const res = await throttle.run(async () => {
      attempts++;
      if (attempts === 1) throw new Error("FLOOD_WAIT_3");
      return "ok";
    }, (s) => pauses.push(s));
    assert.equal(res, "ok");
    assert.equal(attempts, 2);
    assert.deepEqual(pauses, [3]);
    assert.ok(sleeps.some((ms) => ms >= 3000));
  });

  it("turning off personal chats wipes them and wipe() clears storage", async () => {
    const kv = memStore();
    const store = new DiscoveryStore("acc1", kv);
    const chats: DiscoveryChat[] = [
      { id: "c1", title: "Cinema", kind: "channel" },
      { id: "p1", title: "Alice", kind: "personal" },
    ];
    const ctrl = new DiscoveryController(fakeTransport(chats), store, () => "lib");
    ctrl.updateOptions({ includePersonalChats: true });
    ctrl.toggleChat(chats[0]);
    ctrl.toggleChat(chats[1]);
    assert.equal(ctrl.getDoc().chosenChats.length, 2);

    ctrl.updateOptions({ includePersonalChats: false });
    assert.deepEqual(ctrl.getDoc().chosenChats.map((c) => c.id), ["c1"]);

    await assert.rejects(() => ctrl.loadChatVideos("p1"));
    const videos = await ctrl.loadChatVideos("c1");
    assert.equal(videos.length, 1);

    ctrl.wipe();
    assert.equal(ctrl.getDoc().chosenChats.length, 0);
    assert.equal(kv.getItem("cablegram.tv.tgDiscovery.acc1"), null);
  });
});
