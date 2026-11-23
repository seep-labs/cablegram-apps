// Pure TypeScript port of libs/telegram-discovery (spec 008 + tv-telegram-section.md).
// No GramJS imports here so unit tests run fast without Telegram, and non-VITE_TG_DIRECT builds stay small.

export type DiscoveryChatKind = "channel" | "group" | "personal" | "saved" | "bot" | "secret";

export interface DiscoveryChat {
  id: string;
  title: string;
  kind: DiscoveryChatKind;
  pinned?: boolean;
  archived?: boolean;
}

export interface DiscoveryScanResult {
  chat: DiscoveryChat;
  videoCount: number;
  fileCount: number;
  scannedAt: number;
}

export interface DiscoveryVideo {
  chatId: string;
  messageId: number;
  title: string;
  durationSeconds?: number;
  sizeBytes?: number;
  dateUnix: number;
  mimeType?: string;
  remoteUniqueId?: string;
  protectedContent: boolean;
  thumbnailUrl?: string;
}

export interface DiscoveryOptions {
  minDurationSeconds: number; // 0 = any length, default 600 (10 min)
  includePersonalChats: boolean;
  includeSavedMessages: boolean;
}

export const DEFAULT_DISCOVERY_OPTIONS: DiscoveryOptions = {
  minDurationSeconds: 600,
  includePersonalChats: false,
  includeSavedMessages: false,
};

export interface DiscoveryTransport {
  listChats(): Promise<DiscoveryChat[]>;
  countChatVideos(chatId: string, minDurationSeconds: number): Promise<{ videoCount: number; fileCount: number }>;
  listChatVideos(chatId: string, minDurationSeconds: number, offsetId?: number, limit?: number): Promise<DiscoveryVideo[]>;
  forwardToLibrary(libraryChatId: string, chatId: string, messageIds: number[]): Promise<void>;
}

export interface KeyValueStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

const DISCOVERY_KEY_PREFIX = "cablegram.tv.tgDiscovery";

export function discoveryStorageKey(accountId: string): string {
  return `${DISCOVERY_KEY_PREFIX}.${accountId}`;
}

export class DiscoveryGate {
  constructor(
    private readonly getChosenIds: () => Set<string>,
    private readonly getLibraryChatId: () => string | null,
  ) {}

  isAllowedChat(chatId: string): boolean {
    const lib = this.getLibraryChatId();
    if (lib && chatId === lib) return false;
    return this.getChosenIds().has(chatId);
  }

  assertAllowedChat(chatId: string): void {
    if (!this.isAllowedChat(chatId)) {
      throw new Error(`Discovery read refused for unchosen chat: ${chatId}`);
    }
  }
}

export function extractFloodWaitSeconds(err: unknown): number | null {
  if (!err || typeof err !== "object") return null;
  const anyErr = err as { seconds?: unknown; message?: unknown; errorMessage?: unknown };
  if (typeof anyErr.seconds === "number" && anyErr.seconds > 0) return anyErr.seconds;
  const msg = String(anyErr.errorMessage ?? anyErr.message ?? "");
  const m = /(?:FLOOD_WAIT_|retry after )(\d+)/.exec(msg);
  return m ? Number(m[1]) : null;
}

export class RequestThrottle {
  private pausedUntil = 0;
  private nextStart = 0;

  constructor(
    private readonly perSecond = 20,
    private readonly now: () => number = () => Date.now(),
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  ) {}

  async run<T>(fn: () => Promise<T>, onPause?: (seconds: number) => void): Promise<T> {
    while (true) {
      await this.waitSlot();
      try {
        return await fn();
      } catch (err) {
        const waitSec = extractFloodWaitSeconds(err);
        if (!waitSec) throw err;
        this.pausedUntil = Math.max(this.pausedUntil, this.now() + waitSec * 1000);
        onPause?.(waitSec);
      }
    }
  }

  private async waitSlot(): Promise<void> {
    const interval = Math.max(1, Math.floor(1000 / this.perSecond));
    const t = this.now();
    const start = Math.max(t, this.pausedUntil, this.nextStart);
    this.nextStart = start + interval;
    const delay = start - t;
    if (delay > 0) await this.sleep(delay);
  }
}

export interface PersistedDiscoveryDoc {
  chosenChats: DiscoveryChat[];
  scanResults: DiscoveryScanResult[];
  options: DiscoveryOptions;
}

export class DiscoveryStore {
  constructor(
    private readonly accountId: string,
    private readonly store: KeyValueStore,
  ) {}

  load(): PersistedDiscoveryDoc {
    const raw = this.store.getItem(discoveryStorageKey(this.accountId));
    if (!raw) {
      return { chosenChats: [], scanResults: [], options: { ...DEFAULT_DISCOVERY_OPTIONS } };
    }
    try {
      const parsed = JSON.parse(raw) as Partial<PersistedDiscoveryDoc>;
      return {
        chosenChats: Array.isArray(parsed.chosenChats) ? parsed.chosenChats : [],
        scanResults: Array.isArray(parsed.scanResults) ? parsed.scanResults : [],
        options: { ...DEFAULT_DISCOVERY_OPTIONS, ...(parsed.options ?? {}) },
      };
    } catch {
      return { chosenChats: [], scanResults: [], options: { ...DEFAULT_DISCOVERY_OPTIONS } };
    }
  }

  save(doc: PersistedDiscoveryDoc): void {
    this.store.setItem(discoveryStorageKey(this.accountId), JSON.stringify(doc));
  }

  clear(): void {
    this.store.removeItem(discoveryStorageKey(this.accountId));
  }
}

export function filterChatsForDiscovery(
  chats: DiscoveryChat[],
  options: DiscoveryOptions,
  libraryChatId: string | null,
): DiscoveryChat[] {
  return chats.filter((c) => {
    if (libraryChatId && c.id === libraryChatId) return false;
    if (c.kind === "secret" || c.kind === "bot") return false; // bots stay out on TV in v1
    if (c.kind === "personal" && !options.includePersonalChats) return false;
    if (c.kind === "saved" && !options.includeSavedMessages) return false;
    return true;
  });
}

export class DiscoveryController {
  private doc: PersistedDiscoveryDoc;
  private readonly gate: DiscoveryGate;
  private scanAborted = false;

  constructor(
    private readonly transport: DiscoveryTransport,
    private readonly store: DiscoveryStore,
    private readonly getLibraryChatId: () => string | null,
    private readonly throttle = new RequestThrottle(20),
  ) {
    this.doc = this.store.load();
    this.gate = new DiscoveryGate(
      () => new Set(this.doc.chosenChats.map((c) => c.id)),
      this.getLibraryChatId,
    );
  }

  getDoc(): PersistedDiscoveryDoc {
    return this.doc;
  }

  isChosen(chatId: string): boolean {
    return this.doc.chosenChats.some((c) => c.id === chatId);
  }

  toggleChat(chat: DiscoveryChat): boolean {
    const exists = this.isChosen(chat.id);
    if (exists) {
      this.doc.chosenChats = this.doc.chosenChats.filter((c) => c.id !== chat.id);
    } else {
      this.doc.chosenChats = [...this.doc.chosenChats, chat];
    }
    this.store.save(this.doc);
    return !exists;
  }

  updateOptions(next: Partial<DiscoveryOptions>): void {
    const prev = this.doc.options;
    this.doc.options = { ...prev, ...next };
    if (!this.doc.options.includePersonalChats) {
      this.doc.chosenChats = this.doc.chosenChats.filter((c) => c.kind !== "personal");
      this.doc.scanResults = this.doc.scanResults.filter((r) => r.chat.kind !== "personal");
    }
    if (!this.doc.options.includeSavedMessages) {
      this.doc.chosenChats = this.doc.chosenChats.filter((c) => c.kind !== "saved");
      this.doc.scanResults = this.doc.scanResults.filter((r) => r.chat.kind !== "saved");
    }
    if (next.minDurationSeconds !== undefined && next.minDurationSeconds !== prev.minDurationSeconds) {
      this.doc.scanResults = [];
    }
    this.store.save(this.doc);
  }

  stopScan(): void {
    this.scanAborted = true;
  }

  async scan(
    onProgress: (done: number, total: number, results: DiscoveryScanResult[]) => void,
    onPause?: (seconds: number) => void,
  ): Promise<DiscoveryScanResult[]> {
    this.scanAborted = false;
    const all = await this.throttle.run(() => this.transport.listChats(), onPause);
    const eligible = filterChatsForDiscovery(all, this.doc.options, this.getLibraryChatId());
    const existingMap = new Map(this.doc.scanResults.map((r) => [r.chat.id, r]));
    const results: DiscoveryScanResult[] = [];
    let done = 0;

    for (const chat of eligible) {
      if (this.scanAborted) break;
      const cached = existingMap.get(chat.id);
      if (cached) {
        results.push(cached);
        done++;
        onProgress(done, eligible.length, [...results]);
        continue;
      }
      const counts = await this.throttle.run(
        () => this.transport.countChatVideos(chat.id, this.doc.options.minDurationSeconds),
        onPause,
      );
      const entry: DiscoveryScanResult = {
        chat,
        videoCount: counts.videoCount,
        fileCount: counts.fileCount,
        scannedAt: Date.now(),
      };
      results.push(entry);
      this.doc.scanResults = [...results];
      this.store.save(this.doc);
      done++;
      onProgress(done, eligible.length, [...results]);
    }
    return results;
  }

  async loadChatVideos(chatId: string, offsetId = 0, limit = 30): Promise<DiscoveryVideo[]> {
    this.gate.assertAllowedChat(chatId);
    return this.throttle.run(() =>
      this.transport.listChatVideos(chatId, this.doc.options.minDurationSeconds, offsetId, limit),
    );
  }

  async addToLibrary(chatId: string, messageId: number): Promise<void> {
    this.gate.assertAllowedChat(chatId);
    const lib = this.getLibraryChatId();
    if (!lib) throw new Error("library_channel_missing");
    await this.throttle.run(() => this.transport.forwardToLibrary(lib, chatId, [messageId]));
  }

  wipe(): void {
    this.doc = { chosenChats: [], scanResults: [], options: { ...DEFAULT_DISCOVERY_OPTIONS } };
    this.store.clear();
  }
}
