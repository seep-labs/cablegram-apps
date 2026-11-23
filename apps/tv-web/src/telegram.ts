// Direct Telegram playback for TVs that cannot run TDLib: a GramJS client reads the video's bytes over MTProto and the
// tv-media engine plays them. Loaded lazily (import("./telegram")) and only when the app was built with VITE_TG_DIRECT=1.
// Credentials: the application's api_id/api_hash come from the control plane at run time (GET /api/telegram/client-config,
// allowed per TV), never from this repository. The account session is a GramJS StringSession, created by signing in
// through the phone (loginWithPhone) and kept per account by telegram-link.ts. Never log it.
import "./telegram-env";
import { SourceError, TelegramRpcError, TelegramSource } from "tv-media";
import type { TelegramFileApi } from "tv-media";
import bigInt from "big-integer";
import { Api, TelegramClient, errors, helpers } from "telegram";
import { StringSession } from "telegram/sessions";
import { fastModExp } from "./telegram-modpow";
import { peerOf } from "./telegram-identity";
import type { TelegramRef } from "./telegram-identity";
import { loginLink } from "./telegram-link";
import { Slots } from "./telegram-slots";
import type { StandaloneTelegramBackend } from "./telegram-standalone";
import type { DiscoveryChat, DiscoveryChatKind, DiscoveryTransport, DiscoveryVideo } from "./telegram-discovery";

// The auth-key exchange (2048-bit Diffie-Hellman) took ~19 s on a 2019 Samsung TV with GramJS's own modExp. The handshake
// reads helpers.modExp at call time, also in the bundle, so replacing it here is enough.
(helpers as Record<string, unknown>)["modExp"] = fastModExp;

export const SESSION_KEY = "cablegram.tv.tgSession";

/** Progress markers for debugging on a TV or in the live check (never ids, names or credentials). */
const note = (step: string) => console.info(`[tg] ${step}`);

/** A step that never answers must end in an error the TV can show, not an endless "Starting playback". */
function within<T>(step: string, ms: number, work: Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new SourceError("telegram_error", `Telegram did not answer (${step})`, undefined, `timeout_${step}`)), ms);
    work.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); reject(e); });
  });
}

export class TelegramUnavailable extends Error {
  constructor(readonly reason: "no_session" | "no_video" | "not_found" | "protected") {
    super(reason);
  }
}

const WEB_DC_HOSTS: Record<number, string> = {
  1: "pluto.web.telegram.org",
  2: "venus.web.telegram.org",
  3: "aurora.web.telegram.org",
  4: "vesta.web.telegram.org",
  5: "flora.web.telegram.org",
};

let shared: { client: TelegramClient; key: string } | null = null;

type Config = { apiId: number; apiHash: string };

function makeClient(config: Config, session: string): TelegramClient {
  const stored = new StringSession(session);
  // A session string made on a computer (TCP) remembers the data centre's IP address, but a browser can only reach
  // Telegram through its WebSocket hosts: wss://<name>.web.telegram.org. Same auth key, different address.
  const webHost = WEB_DC_HOSTS[stored.dcId];
  if (webHost) stored.setDC(stored.dcId, webHost, 443);
  return new TelegramClient(stored, config.apiId, config.apiHash, {
    connectionRetries: 3,
    useWSS: true,
    floodSleepThreshold: 0, // TelegramSource decides how long to wait
    deviceModel: "Smart TV",
    systemVersion: "1.0",
    appVersion: "TV Web",
  });
}

/** The whole session string identifies the account: two accounts on one data centre have strings of equal length. */
const sessionKey = (config: Config, session: string) => `${config.apiId}:${session}`;

/** Closes the cached connection (another account is taking over), so connections do not pile up. */
async function dropShared(): Promise<void> {
  const old = shared;
  shared = null;
  if (old) { try { await old.client.disconnect(); } catch { /* already closed */ } }
}

async function connect(config: Config, session: string): Promise<TelegramClient> {
  const key = sessionKey(config, session);
  if (shared && shared.key === key) return shared.client;
  await dropShared();
  const client = makeClient(config, session);
  note("connecting");
  await within("connect", 30_000, client.connect());
  note("connected");
  shared = { client, key };
  return client;
}

// ---- signing this TV in, approved on the phone -----------------------------------------------------------------------

/** Why a sign-in ended without a session. */
export class LoginError extends Error {
  constructor(readonly reason: "denied" | "failed" | "cancelled" | "password", readonly code?: string) {
    super(reason);
  }
}

export interface LoginHooks {
  cancelled(): boolean;
  /** Gives the phone a fresh login link to approve (Telegram refreshes it about every 30 s). */
  postLink(link: string): Promise<void>;
  /** What the phone did with the latest link. */
  phoneState(): Promise<{ state: string; error?: string }>;
  /** The account's two-step password, typed on the phone. Rejects when nobody answers. */
  password(hint: string | undefined, attempt: number): Promise<string>;
}

const APPROVAL_WAIT_MS = 25_000;
const MAX_ROUNDS = 12; // about five minutes of fresh links

// A fresh connection first agrees an auth key with Telegram (Diffie-Hellman in JavaScript): about 20 s on a 2019 Samsung
// TV, before the phone could even be asked. So the sign-in connection starts while the viewer reads the "Connect
// Telegram?" card, and its key, which no account has authorised yet, is kept for the next sign-in on this TV. Once a
// phone approves, the key belongs to that account: it is forgotten here and lives on only in the account's session.
const SIGN_IN_KEY = "cablegram.tv.tgSignInKey";
const SIGN_IN_CONNECT_MS = 60_000;

let signIn: { apiId: number; client: TelegramClient; ready: Promise<void> } | null = null;

function keyStore(): Storage | null {
  try { return localStorage; } catch { return null; }
}

function keptKey(apiId: number): string {
  try { return keyStore()?.getItem(`${SIGN_IN_KEY}.${apiId}`) ?? ""; } catch { return ""; }
}

function keepKey(apiId: number, session: string | null): void {
  try {
    if (session) keyStore()?.setItem(`${SIGN_IN_KEY}.${apiId}`, session);
    else keyStore()?.removeItem(`${SIGN_IN_KEY}.${apiId}`);
  } catch { /* storage blocked: the next sign-in agrees a new key */ }
}

/** Starts the connection a sign-in will use, or returns the one already starting. Safe to call more than once. */
export function prepareSignIn(config: Config): Promise<void> {
  if (signIn && signIn.apiId === config.apiId) return signIn.ready;
  const start = (session: string): Promise<void> => {
    const client = makeClient(config, session);
    note(session ? "connecting for sign-in with the kept key" : "connecting for sign-in");
    const entry = { apiId: config.apiId, client, ready: Promise.resolve() };
    entry.ready = within("connect", SIGN_IN_CONNECT_MS, client.connect()).then(
      () => { note("ready for sign-in"); keepKey(config.apiId, client.session.save() as unknown as string); },
      (e) => {
        if (signIn === entry) signIn = null;
        void client.disconnect().catch(() => undefined);
        if (!session) throw e;
        keepKey(config.apiId, null); // Telegram no longer knows the kept key: agree a new one
        return prepareSignIn(config);
      },
    );
    signIn = entry;
    return entry.ready;
  };
  return start(keptKey(config.apiId));
}

/**
 * Telegram "login by QR", but the code is shown to nobody: the phone, which already holds the account, approves the
 * link through Cablegram (contracts/telegram-link.md). Returns this TV's own session string. Telegram's secrets
 * (session, password) are known to Telegram, this TV and the phone only.
 */
export async function loginWithPhone(config: Config, hooks: LoginHooks): Promise<string> {
  await prepareSignIn(config);
  const client = signIn!.client;
  signIn = null; // this sign-in owns the connection now; the next one starts its own
  let tokenUpdate = () => undefined as void;
  const scanned = new Promise<void>((resolve) => { tokenUpdate = resolve; });
  const handler = (update: unknown) => { if (update instanceof Api.UpdateLoginToken) tokenUpdate(); };
  client.addEventHandler(handler);
  const fresh = () => within("sign_in", 30_000, client.invoke(new Api.auth.ExportLoginToken({ apiId: config.apiId, apiHash: config.apiHash, exceptIds: [] })));
  try {
    let approved = false;
    for (let round = 0; round < MAX_ROUNDS && !approved; round++) {
      if (hooks.cancelled()) throw new LoginError("cancelled");
      const result = await fresh();
      if (result instanceof Api.auth.LoginTokenSuccess || result instanceof Api.auth.LoginTokenMigrateTo) { approved = true; break; }
      if (!(result instanceof Api.auth.LoginToken)) throw new LoginError("failed", "UNEXPECTED_RESULT");
      await hooks.postLink(loginLink(result.token));
      note("link posted, waiting for the phone");
      const end = Date.now() + APPROVAL_WAIT_MS;
      let seen = false;
      scanned.then(() => { seen = true; });
      while (Date.now() < end && !seen) {
        if (hooks.cancelled()) throw new LoginError("cancelled");
        await new Promise<void>((r) => setTimeout(r, 1500));
        const phone = await hooks.phoneState().catch(() => ({ state: "pending" as string, error: undefined }));
        if (phone.state === "denied") throw new LoginError("denied");
        if (phone.state === "failed") throw new LoginError("failed", phone.error);
      }
      approved = seen;
    }
    if (!approved) throw new LoginError("failed", "TIMEOUT");
    note("the phone approved");
    keepKey(config.apiId, null); // the key is the account's from here on, whatever happens next

    // Telegram now has an authorisation for this link: collect it (on another data centre if it says so).
    let attempts = 0;
    const finish = async (): Promise<void> => {
      try {
        const done = await fresh();
        if (done instanceof Api.auth.LoginTokenSuccess) return;
        if (done instanceof Api.auth.LoginTokenMigrateTo) {
          await (client as unknown as { _switchDC(dc: number): Promise<void> })._switchDC(done.dcId);
          const moved = await within("sign_in", 30_000, client.invoke(new Api.auth.ImportLoginToken({ token: done.token })));
          if (moved instanceof Api.auth.LoginTokenSuccess) return;
        }
        throw new LoginError("failed", "UNEXPECTED_RESULT");
      } catch (e) {
        if ((e as { errorMessage?: string }).errorMessage !== "SESSION_PASSWORD_NEEDED") throw e;
        note("two-step password needed");
        await client.signInWithPassword({ apiId: config.apiId, apiHash: config.apiHash }, {
          password: async (hint?: string) => {
            attempts++;
            try { return await hooks.password(hint, attempts); } catch { throw new LoginError("password"); }
          },
          onError: async (err: Error) => {
            if (err instanceof LoginError || attempts >= 3) return true; // stop
            return (err as { errorMessage?: string }).errorMessage === "PASSWORD_HASH_INVALID" ? false : true; // wrong: ask again
          },
        });
      }
    };
    await finish();
    note("signed in");
    const session = client.session.save() as unknown as string;
    await dropShared();
    shared = { client, key: sessionKey(config, session) };
    return session;
  } catch (e) {
    try { await client.disconnect(); } catch { /* already closed */ }
    if (e instanceof LoginError) throw e;
    if ((e as Error).message === "AUTH_USER_CANCEL") throw new LoginError("password");
    throw e;
  } finally {
    try { client.removeEventHandler(handler, undefined as never); } catch { /* the client is closed anyway */ }
  }
}

/** Ends this TV's Telegram session on Telegram's side (the phone asked for it, or the household disconnected). */
export async function signOut(config: Config, session: string): Promise<void> {
  const client = await connect(config, session);
  try { await within("log_out", 20_000, client.invoke(new Api.auth.LogOut())); } catch { /* already ended: that is the goal */ }
  try { await client.disconnect(); } catch { /* ignore */ }
  shared = null;
}

/** Turns a GramJS failure into the RPC message TelegramSource understands. */
export function rpcMessage(e: unknown): string | null {
  const err = e as { errorMessage?: string; seconds?: number; newDc?: number };
  if (e instanceof errors.FloodWaitError) return `FLOOD_WAIT_${err.seconds ?? 5}`;
  if (e instanceof errors.FileMigrateError) return `FILE_MIGRATE_${err.newDc}`;
  return typeof err.errorMessage === "string" ? err.errorMessage : null;
}

export async function openTelegramSource(
  ref: TelegramRef,
  config: { apiId: number; apiHash: string },
  session: string | null,
): Promise<TelegramSource> {
  if (!session) throw new TelegramUnavailable("no_session");
  const client = await connect(config, session);

  const peer = peerOf(ref.chatId);
  const resolve = async () => {
    const id = peer.kind === "channel" ? Number(`-100${peer.id}`) : Number(ref.chatId);
    // A fresh session does not know the chat's access hash. Reading the dialog list finds it (and fills GramJS's cache);
    // stop at the chat instead of loading every dialog. getInputEntity alone can wait on the network for a long time.
    note("looking for the chat in the dialog list");
    const found = within("dialogs", 60_000, client.getDialogs({ limit: 300 }).then((dialogs) => {
      const hit = dialogs.find((d) => d.id !== undefined && d.id.toString() === ref.chatId);
      return hit ? hit.inputEntity : null;
    }));
    const entity = await found;
    if (entity) return entity;
    note("chat not in the dialog list, asking Telegram directly");
    return within("find_chat", 20_000, client.getInputEntity(id));
  };
  const inputPeer = await resolve();
  note("chat found");

  const fetchDocument = async (): Promise<Api.Document> => {
    const [message] = await within("message", 30_000, client.getMessages(inputPeer, { ids: [ref.messageId] }));
    if (!message) throw new TelegramUnavailable("not_found");
    // "Restrict saving content" on the message or its channel: Cablegram doesn't get around it.
    if (message.noforwards || (message.chat as { noforwards?: boolean } | undefined)?.noforwards) throw new TelegramUnavailable("protected");
    const media = message.media;
    if (!(media instanceof Api.MessageMediaDocument) || !(media.document instanceof Api.Document)) throw new TelegramUnavailable("no_video");
    return media.document;
  };

  let doc = await fetchDocument();
  note(`video message found (${Math.round(doc.size.toJSNumber() / 1048576)} MB, ${doc.mimeType})`);
  const location = () =>
    new Api.InputDocumentFileLocation({ id: doc.id, accessHash: doc.accessHash, fileReference: doc.fileReference, thumbSize: "" });
  // Download from a connection of its own to the file's data centre (the plan's §7), not the one that carries the chat
  // requests. FILE_MIGRATE can still move it.
  let dc: number | null = doc.dcId !== client.session.dcId ? doc.dcId : null;
  const slots = new Slots(2);

  const api: TelegramFileApi = {
    async getFile(offset, limit, signal) {
      try {
        const result = await slots.run(signal, () => {
          const request = new Api.upload.GetFile({ location: location(), offset: bigInt(offset), limit, precise: false, cdnSupported: false });
          return within("download", 45_000, dc === null ? client.invoke(request) : client.getSender(dc).then((sender) => sender.send(request)));
        });
        if (!(result instanceof Api.upload.File)) throw new TelegramRpcError("CDN_REDIRECT_UNSUPPORTED");
        const b = result.bytes;
        if (offset === 0 || offset % (16 * 1048576) === 0) note(`downloaded a chunk at ${Math.round(offset / 1048576)} MB`);
        return new Uint8Array(b.buffer, b.byteOffset, b.length);
      } catch (e) {
        if (e instanceof SourceError) throw e;
        const rpc = rpcMessage(e);
        if (rpc) throw new TelegramRpcError(rpc);
        throw e;
      }
    },
    async refreshReference() {
      doc = await fetchDocument();
    },
    async migrate(newDc) {
      dc = newDc;
    },
  };
  return new TelegramSource(api, doc.size.toJSNumber());
}

// ---- standalone TV sign-in & bot start -------------------------------------------------------------------------------

export async function createStandaloneBackend(config: Config): Promise<StandaloneTelegramBackend> {
  await dropShared();
  const client = makeClient(config, "");
  await within("connect", 30_000, client.connect());
  let currentPhoneCodeHash = "";
  let currentPhoneNumber = "";

  const startBotOnClient = async (botUsername: string, startParam: string) => {
    const bot = await client.getInputEntity(botUsername);
    await client.invoke(new Api.messages.StartBot({
      bot,
      peer: bot,
      randomId: bigInt(Math.floor(Math.random() * 1e15)),
      startParam,
    }));
  };

  return {
    async exportQrLoginToken(exceptIds: number[]) {
      const r = await client.invoke(new Api.auth.ExportLoginToken({
        apiId: config.apiId,
        apiHash: config.apiHash,
        exceptIds: exceptIds.map((id) => bigInt(id)),
      }));
      if (r instanceof Api.auth.LoginTokenSuccess) {
        const me = await client.getMe();
        return { sessionString: String(client.session.save()), userId: String((me as Api.User).id) };
      }
      if (r instanceof Api.auth.LoginTokenMigrateTo) {
        await client._switchDC(r.dcId);
        const migrated = await client.invoke(new Api.auth.ImportLoginToken({ token: r.token }));
        if (migrated instanceof Api.auth.LoginTokenSuccess) {
          const me = await client.getMe();
          return { sessionString: String(client.session.save()), userId: String((me as Api.User).id) };
        }
      }
      if (r instanceof Api.auth.LoginToken) {
        return { token: new Uint8Array(r.token), expires: r.expires };
      }
      throw new Error("UNEXPECTED_LOGIN_TOKEN");
    },

    async sendPhoneCode(phoneNumber: string) {
      currentPhoneNumber = phoneNumber;
      const sent = await client.invoke(new Api.auth.SendCode({
        phoneNumber,
        apiId: config.apiId,
        apiHash: config.apiHash,
        settings: new Api.CodeSettings({}),
      }));
      if (!("phoneCodeHash" in sent)) throw new Error("SEND_CODE_FAILED");
      currentPhoneCodeHash = String(sent.phoneCodeHash);
      return { phoneCodeHash: currentPhoneCodeHash };
    },

    async signInWithCode(phoneNumber: string, phoneCodeHash: string, code: string) {
      try {
        await client.invoke(new Api.auth.SignIn({
          phoneNumber,
          phoneCodeHash: phoneCodeHash || currentPhoneCodeHash,
          phoneCode: code,
        }));
        const me = await client.getMe();
        return { kind: "signed_in", sessionString: String(client.session.save()), userId: String((me as Api.User).id) };
      } catch (e) {
        const msg = rpcMessage(e) ?? String((e as { message?: unknown })?.message ?? "");
        if (msg.includes("SESSION_PASSWORD_NEEDED")) {
          return { kind: "password_required" };
        }
        throw e;
      }
    },

    async signInWithPassword(password: string) {
      const srp = await client.invoke(new Api.account.GetPassword());
      const { computeCheck } = await import("telegram/Password");
      const check = await computeCheck(srp, password);
      await client.invoke(new Api.auth.CheckPassword({ password: check }));
      const me = await client.getMe();
      return { sessionString: String(client.session.save()), userId: String((me as Api.User).id) };
    },

    async startBot(botUsername: string, startParam: string) {
      await startBotOnClient(botUsername, startParam);
    },

    async unblockAndStartBot(botUsername: string, startParam: string) {
      const bot = await client.getInputEntity(botUsername);
      await client.invoke(new Api.contacts.Unblock({ id: bot }));
      await startBotOnClient(botUsername, startParam);
    },

    async disconnect() {
      try { await client.disconnect(); } catch { /* ignore */ }
    },
  };
}

export async function sendBotStartWithSession(
  config: Config,
  session: string,
  botUsername: string,
  startParam: string,
  unblock = false,
): Promise<void> {
  const client = await connect(config, session);
  const bot = await client.getInputEntity(botUsername);
  if (unblock) {
    await client.invoke(new Api.contacts.Unblock({ id: bot }));
  }
  await client.invoke(new Api.messages.StartBot({
    bot,
    peer: bot,
    randomId: bigInt(Math.floor(Math.random() * 1e15)),
    startParam,
  }));
}

export async function getSelfUserIdWithSession(config: Config, session: string): Promise<string> {
  const client = await connect(config, session);
  const me = await client.getMe();
  return String((me as Api.User).id);
}

// ---- TV Telegram section discovery transport (GramJS) ---------------------------------------------------------------

export function createGramJsDiscoveryTransport(config: Config, session: string): DiscoveryTransport {
  const MIN_FILE_BYTES = 100 * 1024 * 1024; // 100 MB (spec 008 FR-006)

  const classifyDialog = (d: { isChannel?: boolean; isGroup?: boolean; entity?: unknown }): DiscoveryChatKind => {
    const entity = d.entity as { className?: string; bot?: boolean; isSelf?: boolean } | undefined;
    if (entity?.className === "EncryptedChat") return "secret";
    if (entity?.isSelf) return "saved";
    if (entity?.bot) return "bot";
    if (d.isChannel && !d.isGroup) return "channel";
    if (d.isGroup) return "group";
    return "personal";
  };

  const extractVideoMeta = (msg: Api.Message): DiscoveryVideo | null => {
    const media = msg.media;
    if (!(media instanceof Api.MessageMediaDocument) || !(media.document instanceof Api.Document)) return null;
    const doc = media.document;
    if (!doc.mimeType?.startsWith("video/")) return null;
    const videoAttr = doc.attributes.find((a): a is Api.DocumentAttributeVideo => a instanceof Api.DocumentAttributeVideo);
    const fileAttr = doc.attributes.find((a): a is Api.DocumentAttributeFilename => a instanceof Api.DocumentAttributeFilename);
    const captionLine = (msg.message ?? "").split("\n").map((s) => s.trim()).find(Boolean);
    const title = captionLine || fileAttr?.fileName || `Video ${msg.id}`;
    return {
      chatId: String(msg.chatId ?? ""),
      messageId: msg.id,
      title,
      durationSeconds: videoAttr?.duration,
      sizeBytes: doc.size.toJSNumber(),
      dateUnix: msg.date,
      mimeType: doc.mimeType,
      remoteUniqueId: undefined,
      protectedContent: msg.noforwards === true,
    };
  };

  return {
    async listChats(): Promise<DiscoveryChat[]> {
      const client = await connect(config, session);
      const dialogs = await client.getDialogs({ limit: 200 });
      return dialogs.map((d) => ({
        id: String(d.id),
        title: d.title || "Untitled",
        kind: classifyDialog(d),
        pinned: d.pinned === true,
        archived: d.archived === true,
      }));
    },

    async countChatVideos(chatId: string, minDurationSeconds: number): Promise<{ videoCount: number; fileCount: number }> {
      const client = await connect(config, session);
      const peer = await client.getInputEntity(chatId);
      const videoRes = await client.invoke(new Api.messages.Search({
        peer,
        q: "",
        filter: new Api.InputMessagesFilterVideo(),
        minDate: 0,
        maxDate: 0,
        offsetId: 0,
        addOffset: 0,
        limit: 100,
        maxId: 0,
        minId: 0,
        hash: bigInt.zero,
      }));
      const docRes = await client.invoke(new Api.messages.Search({
        peer,
        q: "",
        filter: new Api.InputMessagesFilterDocument(),
        minDate: 0,
        maxDate: 0,
        offsetId: 0,
        addOffset: 0,
        limit: 100,
        maxId: 0,
        minId: 0,
        hash: bigInt.zero,
      }));
      let videoCount = 0;
      let fileCount = 0;
      const countFrom = (res: Api.messages.TypeMessages, isDoc: boolean) => {
        if (!("messages" in res)) return;
        for (const m of res.messages) {
          if (!(m instanceof Api.Message)) continue;
          const v = extractVideoMeta(m);
          if (!v) continue;
          if (!isDoc && v.durationSeconds !== undefined) {
            if (minDurationSeconds === 0 || v.durationSeconds >= minDurationSeconds) videoCount++;
          } else if ((v.sizeBytes ?? 0) >= (minDurationSeconds === 0 ? 0 : MIN_FILE_BYTES)) {
            fileCount++;
          }
        }
      };
      countFrom(videoRes, false);
      countFrom(docRes, true);
      return { videoCount, fileCount };
    },

    async listChatVideos(chatId: string, minDurationSeconds: number, offsetId = 0, limit = 30): Promise<DiscoveryVideo[]> {
      const client = await connect(config, session);
      const peer = await client.getInputEntity(chatId);
      const res = await client.invoke(new Api.messages.Search({
        peer,
        q: "",
        filter: new Api.InputMessagesFilterVideo(),
        minDate: 0,
        maxDate: 0,
        offsetId,
        addOffset: 0,
        limit,
        maxId: 0,
        minId: 0,
        hash: bigInt.zero,
      }));
      const out: DiscoveryVideo[] = [];
      if ("messages" in res) {
        for (const m of res.messages) {
          if (!(m instanceof Api.Message)) continue;
          const v = extractVideoMeta(m);
          if (!v) continue;
          v.chatId = chatId;
          if (v.durationSeconds === undefined || minDurationSeconds === 0 || v.durationSeconds >= minDurationSeconds) {
            out.push(v);
          }
        }
      }
      return out;
    },

    async forwardToLibrary(libraryChatId: string, chatId: string, messageIds: number[]): Promise<void> {
      const client = await connect(config, session);
      const fromPeer = await client.getInputEntity(chatId);
      const toPeer = await client.getInputEntity(libraryChatId);
      await client.invoke(new Api.messages.ForwardMessages({
        fromPeer,
        toPeer,
        id: messageIds,
        randomId: messageIds.map(() => bigInt(Math.floor(Math.random() * 1e15))),
      }));
    },
  };
}
