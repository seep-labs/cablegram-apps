import { describe, it } from "vitest";
import assert from "node:assert/strict";
import {
  formatFloodWaitMinutes,
  mapTelegramError,
  runQrSignInLoop,
  verifyTelegramUserIdMatch,
  type StandaloneTelegramBackend,
} from "../src/telegram-standalone";

describe("tv-web telegram-standalone", () => {
  it("runQrSignInLoop refreshes QR links until LoginTokenSuccess", async () => {
    let calls = 0;
    const links: string[] = [];
    const backend: StandaloneTelegramBackend = {
      async exportQrLoginToken() {
        calls++;
        if (calls < 3) {
          return { token: new Uint8Array([1, 2, 3, calls]), expires: Math.floor(Date.now() / 1000) + 1 };
        }
        return { sessionString: "sess-1", userId: "42" };
      },
      async sendPhoneCode() { return { phoneCodeHash: "h" }; },
      async signInWithCode() { return { kind: "signed_in", sessionString: "s", userId: "42" }; },
      async signInWithPassword() { return { sessionString: "s", userId: "42" }; },
      async startBot() {},
      async unblockAndStartBot() {},
      async disconnect() {},
    };
    const res = await runQrSignInLoop(backend, (l) => links.push(l), () => false, async () => {});
    assert.equal(res.sessionString, "sess-1");
    assert.equal(res.userId, "42");
    assert.equal(links.length, 2);
    assert.ok(links[0].startsWith("tg://login?token="));
  });

  it("runQrSignInLoop expires after 5 minutes", async () => {
    let now = 0;
    const backend: StandaloneTelegramBackend = {
      async exportQrLoginToken() { return { token: new Uint8Array([1]), expires: 30 }; },
      async sendPhoneCode() { return { phoneCodeHash: "h" }; },
      async signInWithCode() { return { kind: "signed_in", sessionString: "s", userId: "1" }; },
      async signInWithPassword() { return { sessionString: "s", userId: "1" }; },
      async startBot() {},
      async unblockAndStartBot() {},
      async disconnect() {},
    };
    await assert.rejects(
      () => runQrSignInLoop(backend, () => {}, () => false, async () => { now += 60_000; }, () => now, 5 * 60 * 1000),
      /QR_EXPIRED_5M/,
    );
  });

  it("maps Telegram RPC errors and checks user id match", () => {
    assert.deepEqual(mapTelegramError({ errorMessage: "FLOOD_WAIT_120" }), { code: "FLOOD_WAIT", floodSeconds: 120 });
    assert.equal(formatFloodWaitMinutes(120), "Try again in 2 minutes.");
    assert.deepEqual(mapTelegramError(new Error("PHONE_NUMBER_INVALID")), { code: "PHONE_NUMBER_INVALID" });
    assert.deepEqual(mapTelegramError(new Error("PHONE_CODE_EXPIRED")), { code: "PHONE_CODE_INVALID" });
    assert.deepEqual(mapTelegramError(new Error("PASSWORD_HASH_INVALID")), { code: "PASSWORD_HASH_INVALID" });

    assert.equal(verifyTelegramUserIdMatch("12345", "12345"), true);
    assert.equal(verifyTelegramUserIdMatch("12345", "99999"), false);
    assert.equal(verifyTelegramUserIdMatch(null, "12345"), false);
  });
});
