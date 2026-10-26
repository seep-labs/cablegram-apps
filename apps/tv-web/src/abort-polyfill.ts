// AbortController arrived in Chromium 66; Samsung's 2019 TVs (Tizen 5.0) run Chromium 63, where the engine's
// `new AbortController()` threw and playback stuck on "Starting playback…". That fetch also ignores `signal`, so a
// cancelled range request would keep downloading and keep its relay stream open: requests that carry a signal go
// through XMLHttpRequest instead, which really aborts.

type Listener = (this: AbortSignal, ev: Event) => void;

class AbortSignalShim {
  aborted = false;
  reason: unknown = undefined;
  onabort: Listener | null = null;
  private listeners: Listener[] = [];

  addEventListener(type: string, fn: Listener): void {
    if (type === "abort" && this.listeners.indexOf(fn) < 0) this.listeners.push(fn);
  }

  removeEventListener(type: string, fn: Listener): void {
    if (type === "abort") this.listeners = this.listeners.filter((l) => l !== fn);
  }

  dispatchEvent(ev: Event): boolean {
    const self = this as unknown as AbortSignal;
    this.onabort?.call(self, ev);
    // `{ once: true }` listeners are not tracked separately: abort fires at most once anyway.
    this.listeners.slice().forEach((l) => l.call(self, ev));
    this.listeners = [];
    return true;
  }
}

class AbortControllerShim {
  readonly signal = new AbortSignalShim();

  abort(reason?: unknown): void {
    if (this.signal.aborted) return;
    this.signal.aborted = true;
    this.signal.reason = reason ?? abortError();
    this.signal.dispatchEvent({ type: "abort" } as Event);
  }
}

function abortError(): Error {
  const e = new Error("The operation was aborted.");
  e.name = "AbortError";
  return e;
}

function parseHeaders(raw: string): Headers {
  const headers = new Headers();
  raw.trim().split(/[\r\n]+/).forEach((line) => {
    const at = line.indexOf(":");
    if (at > 0) headers.append(line.slice(0, at).trim(), line.slice(at + 1).trim());
  });
  return headers;
}

/** A fetch for GET-style requests that honours `signal` by aborting an XMLHttpRequest. */
export function xhrFetch(input: string, init: RequestInit = {}): Promise<Response> {
  return new Promise((resolve, reject) => {
    const signal = init.signal;
    if (signal?.aborted) return reject(abortError());
    const xhr = new XMLHttpRequest();
    xhr.open(init.method ?? "GET", input);
    xhr.responseType = "arraybuffer";
    const headers = init.headers as Record<string, string> | undefined;
    if (headers) Object.keys(headers).forEach((k) => xhr.setRequestHeader(k, headers[k]));
    const onAbort = () => xhr.abort();
    signal?.addEventListener("abort", onAbort);
    const done = () => signal?.removeEventListener("abort", onAbort);
    xhr.onload = () => {
      done();
      resolve(new Response(xhr.response as ArrayBuffer, { status: xhr.status, statusText: xhr.statusText, headers: parseHeaders(xhr.getAllResponseHeaders()) }));
    };
    xhr.onerror = () => { done(); reject(new TypeError("Failed to fetch")); };
    xhr.onabort = () => { done(); reject(abortError()); };
    xhr.send((init.body as XMLHttpRequestBodyInit | null | undefined) ?? null);
  });
}

/** Installs the shims on `target` when it has no AbortController; returns whether it did. */
export function installAbortPolyfill(target: Record<string, unknown> = globalThis as unknown as Record<string, unknown>): boolean {
  if (typeof target.AbortController === "function") return false;
  target.AbortController = AbortControllerShim;
  target.AbortSignal = AbortSignalShim;
  const nativeFetch = target.fetch as typeof fetch | undefined;
  if (nativeFetch) {
    target.fetch = (input: RequestInfo | URL, init?: RequestInit) =>
      init?.signal && typeof input === "string" ? xhrFetch(input, init) : nativeFetch.call(target, input, init);
  }
  return true;
}

installAbortPolyfill();
