/**
 * The TV's live connection to the server. The server sends `{type:"changed", scope}` hints on the command socket
 * when something the TV shows may have changed; this module only delivers them. It never reads data from a
 * message: the caller refetches over REST.
 *
 * Browsers cannot set headers on a WebSocket, so each attempt first trades the TV's bearer token for a
 * single-use ticket (about 30 s) that goes in the URL.
 */

export type LiveScope = "catalog" | "profiles" | "profileSwitch";
export interface LiveChange { scope: LiveScope; profileId?: string }

export interface SocketLike {
  onopen: ((e: unknown) => void) | null;
  onmessage: ((e: { data: unknown }) => void) | null;
  onclose: ((e: unknown) => void) | null;
  onerror: ((e: unknown) => void) | null;
  close(): void;
}

export interface LiveDeps {
  /** A fresh single-use ticket. Rejecting with an object whose `status` is 401/403 stops retrying. */
  ticket(): Promise<string>;
  socketUrl(ticket: string): string;
  onChanged(change: LiveChange): void;
  /** true once the server said `ready`, false whenever the socket is lost. */
  onState?(up: boolean): void;
  createSocket?(url: string): SocketLike;
  random?: () => number;
  setTimer?: (fn: () => void, ms: number) => number;
  clearTimer?: (id: number) => void;
}

export interface Live {
  stop(): void;
  /**
   * The app came to the foreground or the network returned: reconnect now instead of waiting. A socket that
   * looks up is left alone unless `force` (a TV that slept can hold a dead one).
   */
  nudge(force?: boolean): void;
  isUp(): boolean;
}

const BASE_MS = 1_000;
const MAX_MS = 30_000;
const STABLE_MS = 10_000;
/** Bursts from the server are already coalesced; this absorbs what is left. */
const DEBOUNCE_MS = 300;

/** 1 s doubling to 30 s, spread by ±30% so a whole street of TVs does not reconnect in the same second. */
export function backoffDelay(attempt: number, random: () => number = Math.random): number {
  const base = Math.min(MAX_MS, BASE_MS * Math.pow(2, Math.max(0, attempt)));
  return Math.round(base * (0.7 + random() * 0.6));
}

export function parseChange(data: unknown): LiveChange | null {
  if (typeof data !== "string") return null;
  let msg: { type?: unknown; scope?: unknown; profileId?: unknown };
  try { msg = JSON.parse(data); } catch { return null; }
  if (!msg || msg.type !== "changed") return null;
  if (msg.scope !== "catalog" && msg.scope !== "profiles" && msg.scope !== "profileSwitch") return null;
  return { scope: msg.scope, ...(typeof msg.profileId === "string" ? { profileId: msg.profileId } : {}) };
}

export function startLive(deps: LiveDeps): Live {
  const createSocket = deps.createSocket ?? ((url: string) => new WebSocket(url) as unknown as SocketLike);
  const random = deps.random ?? Math.random;
  const setTimer = deps.setTimer ?? ((fn, ms) => window.setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((id) => window.clearTimeout(id));

  let stopped = false;
  let up = false;
  let attempt = 0;
  let socket: SocketLike | null = null;
  let retryTimer: number | undefined;
  let stableTimer: number | undefined;
  /** Bumped on every connection attempt so a late callback from an older one is ignored. */
  let generation = 0;
  const debounce = new Map<string, number>();

  function setUp(next: boolean) {
    if (up === next) return;
    up = next;
    deps.onState?.(next);
  }

  function emit(change: LiveChange) {
    const key = `${change.scope}|${change.profileId ?? ""}`;
    const old = debounce.get(key);
    if (old !== undefined) clearTimer(old);
    debounce.set(key, setTimer(() => { debounce.delete(key); if (!stopped) deps.onChanged(change); }, DEBOUNCE_MS));
  }

  function drop() {
    generation++;
    if (stableTimer !== undefined) clearTimer(stableTimer);
    stableTimer = undefined;
    if (socket) {
      const old = socket;
      socket = null;
      old.onopen = old.onmessage = old.onclose = old.onerror = null;
      try { old.close(); } catch { /* already closed */ }
    }
    setUp(false);
  }

  function schedule() {
    if (stopped || retryTimer !== undefined) return;
    retryTimer = setTimer(() => { retryTimer = undefined; void connect(); }, backoffDelay(attempt++, random));
  }

  async function connect() {
    if (stopped || socket) return;
    const mine = ++generation;
    let ticket: string;
    try {
      ticket = await deps.ticket();
    } catch (e) {
      if (mine !== generation || stopped) return;
      const status = (e as { status?: number } | null)?.status;
      // The token is no longer accepted; the app's own flow handles that, so stop knocking.
      if (status === 401 || status === 403) { stopped = true; return; }
      schedule();
      return;
    }
    if (mine !== generation || stopped) return;
    let s: SocketLike;
    try { s = createSocket(deps.socketUrl(ticket)); } catch { schedule(); return; }
    socket = s;
    s.onmessage = (e) => {
      if (mine !== generation) return;
      if (typeof e.data === "string" && e.data.indexOf('"ready"') >= 0) {
        try {
          if ((JSON.parse(e.data) as { type?: unknown }).type === "ready") {
            setUp(true);
            // A connection that held for a while counts as healthy; a flapping one keeps backing off.
            stableTimer = setTimer(() => { attempt = 0; }, STABLE_MS);
            return;
          }
        } catch { /* fall through to the change parser, which ignores it */ }
      }
      const change = parseChange(e.data);
      if (change) emit(change);
    };
    const lost = () => {
      if (mine !== generation) return;
      drop();
      schedule();
    };
    s.onclose = lost;
    s.onerror = lost;
  }

  void connect();

  return {
    stop() {
      stopped = true;
      if (retryTimer !== undefined) clearTimer(retryTimer);
      retryTimer = undefined;
      debounce.forEach((id) => clearTimer(id));
      debounce.clear();
      drop();
    },
    nudge(force = false) {
      if (stopped || (up && !force)) return;
      if (retryTimer !== undefined) { clearTimer(retryTimer); retryTimer = undefined; }
      attempt = 0;
      drop();
      void connect();
    },
    isUp: () => up,
  };
}
