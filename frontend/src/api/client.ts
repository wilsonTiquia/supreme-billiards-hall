import { ApiError, NETWORK_ERROR } from './errors';

/**
 * The single door to the backend. Nothing else in the app calls fetch.
 *
 * Three things are guaranteed here so they cannot be forgotten at a call site:
 *   - `credentials: 'include'`, because auth is a JSESSIONID cookie and not a token;
 *   - the `{ data, message, success }` envelope is unwrapped, so callers receive payloads;
 *   - every failure becomes an ApiError carrying message, status and the optional code.
 */

const BASE = '/api/v1';

/** Envelope shape from API-CONTRACT §1. `code` is absent unless the server sets it. */
interface Envelope<T> {
  data: T;
  message: string;
  success: boolean;
  code?: string;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  /** Query parameters; undefined and null values are dropped rather than sent as "undefined". */
  query?: Record<string, string | number | boolean | undefined | null>;
  /**
   * Suppresses the global 401 handler. Only two calls set this: login, where a 401 is a form
   * error rather than a dead session, and the boot-time `me` probe, where a 401 simply means
   * nobody has logged in yet. Everywhere else a 401 must route to login.
   */
  skipAuthRedirect?: boolean;
  signal?: AbortSignal;
}

/* ── The 401 hook ────────────────────────────────────────────────────────────────────
   Registered by AuthProvider at mount. Kept as a module-level slot rather than an import
   so this file stays free of React and there is no cycle between client and provider. */

type UnauthorizedHandler = () => void;
let onUnauthorized: UnauthorizedHandler | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  onUnauthorized = handler;
}

/** One construction for every "the request never got there" case, so they retry alike. */
function unreachable(): ApiError {
  return new ApiError(
    'The backend is not responding. It may be starting up or stopped.',
    NETWORK_ERROR,
  );
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = `${BASE}${path}`;
  if (!query) return url;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null) params.set(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${url}?${qs}` : url;
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const init: RequestInit = {
    method: options.method ?? 'GET',
    // Not negotiable, and set here so it cannot be omitted anywhere.
    credentials: 'include',
    signal: options.signal,
  };

  if (options.body !== undefined) {
    if (options.body instanceof FormData) {
      // Let the browser set the multipart boundary itself.
      init.body = options.body;
    } else {
      init.headers = { 'Content-Type': 'application/json' };
      init.body = JSON.stringify(options.body);
    }
  }

  try {
    return await fetch(buildUrl(path, options.query), init);
  } catch (cause) {
    // The backend is down or the network is gone. Surfaced as an ordinary ApiError so the
    // UI degrades with a message instead of an unhandled rejection and a white screen.
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw unreachable();
  }
}

/** Issues a request, unwraps the envelope, and returns `data`. Throws ApiError otherwise. */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);

  if (response.status === 401 && !options.skipAuthRedirect) {
    onUnauthorized?.();
  }

  let envelope: Envelope<T> | null = null;
  try {
    envelope = (await response.json()) as Envelope<T>;
  } catch {
    // Our backend always answers with the envelope, so an error whose body is not JSON did
    // not come from it — it came from something in between. In development that is the Vite
    // proxy turning a dead upstream into a 500; in production it is a gateway. Either way
    // the request never reached the POS, which is the offline case, not a server bug.
    if (!response.ok) throw unreachable();
    throw new ApiError('The server returned a response that could not be read.', response.status);
  }

  // Both halves matter: a 200 carrying success:false is still a failure.
  if (!response.ok || !envelope.success) {
    throw new ApiError(envelope.message, response.status, envelope.code);
  }

  return envelope.data;
}

/**
 * The one endpoint that is not enveloped: GET /payments/{id}/photo returns raw image bytes.
 * Unused until the admin screens in Phase F, but it belongs beside its sibling so the rule
 * "no fetch outside this file" holds without an exception later.
 */
export async function requestBlob(path: string, options: RequestOptions = {}): Promise<Blob> {
  const response = await send(path, options);

  if (response.status === 401 && !options.skipAuthRedirect) {
    onUnauthorized?.();
  }

  if (!response.ok) {
    // An error on this route still comes back enveloped; the bytes only arrive on success.
    let message = `The server returned ${response.status}.`;
    let code: string | undefined;
    try {
      const envelope = (await response.json()) as Envelope<never>;
      message = envelope.message;
      code = envelope.code;
    } catch {
      /* keep the status-based fallback */
    }
    throw new ApiError(message, response.status, code);
  }

  return response.blob();
}
