// Standalone TV Telegram sign-in state machine & bot start helpers.
// GramJS calls are behind StandaloneTelegramBackend so unit tests run without Telegram.

export type StandaloneSignInError =
  | { kind: "flood_wait"; seconds: number; message: string }
  | { kind: "qr_expired"; message: string }
  | { kind: "phone_invalid"; message: string }
  | { kind: "code_invalid"; message: string }
  | { kind: "password_invalid"; remainingTries: number; message: string }
  | { kind: "password_locked"; message: string }
  | { kind: "bot_unreachable"; message: string }
  | { kind: "ambiguous"; message: string }
  | { kind: "user_mismatch"; message: string };

export interface StandaloneTelegramBackend {
  exportQrLoginToken(exceptIds: number[]): Promise<{ token: Uint8Array; expires: number } | { sessionString: string; userId: string }>;
  sendPhoneCode(phoneNumber: string): Promise<{ phoneCodeHash: string }>;
  signInWithCode(phoneNumber: string, phoneCodeHash: string, code: string): Promise<{ kind: "signed_in"; sessionString: string; userId: string } | { kind: "password_required" }>;
  signInWithPassword(password: string): Promise<{ sessionString: string; userId: string }>;
  startBot(botUsername: string, startParam: string): Promise<void>;
  unblockAndStartBot(botUsername: string, startParam: string): Promise<void>;
  disconnect(): Promise<void>;
}

export function mapTelegramError(err: unknown): { code: string; floodSeconds?: number } {
  const msg = String((err as { errorMessage?: unknown; message?: unknown })?.errorMessage ?? (err as { message?: unknown })?.message ?? err ?? "");
  const flood = /FLOOD_WAIT_(\d+)/.exec(msg);
  if (flood) return { code: "FLOOD_WAIT", floodSeconds: Number(flood[1]) };
  if (/PHONE_NUMBER_INVALID/.test(msg)) return { code: "PHONE_NUMBER_INVALID" };
  if (/PHONE_CODE_INVALID|PHONE_CODE_EXPIRED/.test(msg)) return { code: "PHONE_CODE_INVALID" };
  if (/PASSWORD_HASH_INVALID/.test(msg)) return { code: "PASSWORD_HASH_INVALID" };
  return { code: msg || "UNKNOWN" };
}

export function formatFloodWaitMinutes(seconds: number): string {
  const mins = Math.max(1, Math.ceil(seconds / 60));
  return `Try again in ${mins} ${mins === 1 ? "minute" : "minutes"}.`;
}

export async function runQrSignInLoop(
  backend: StandaloneTelegramBackend,
  onQrLink: (loginLink: string) => void,
  isCancelled: () => boolean,
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  now: () => number = () => Date.now(),
  maxDurationMs = 5 * 60 * 1000,
): Promise<{ sessionString: string; userId: string }> {
  const startedAt = now();
  while (!isCancelled()) {
    if (now() - startedAt >= maxDurationMs) {
      throw new Error("QR_EXPIRED_5M");
    }
    const res = await backend.exportQrLoginToken([]);
    if ("sessionString" in res) {
      return res;
    }
    const b64url = btoa(String.fromCharCode(...res.token)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    onQrLink(`tg://login?token=${b64url}`);
    const waitMs = Math.min(30_000, Math.max(1_000, res.expires * 1000 - now()));
    await sleep(waitMs);
  }
  throw new Error("CANCELLED");
}

export function verifyTelegramUserIdMatch(expectedUserId: string | null | undefined, actualUserId: string): boolean {
  if (!expectedUserId) return false;
  return String(expectedUserId) === String(actualUserId);
}
