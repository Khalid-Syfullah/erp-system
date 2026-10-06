// A small API client for seeding and end-to-end tests: cookie session, CSRF token, Origin header,
// TOTP second factor and invitation tokens from Mailpit. It talks to the backend like the SPA does.
import { createHmac } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

export const API_URL = process.env.ERP_API_URL ?? 'http://localhost:8080';
export const APP_ORIGIN = process.env.ERP_APP_ORIGIN ?? 'http://localhost:5173';
export const MAILPIT_URL = process.env.ERP_MAILPIT_URL ?? 'http://localhost:8025';

export class ApiFailure extends Error {
  readonly status: number;
  readonly code: string;
  readonly body: unknown;

  constructor(status: number, code: string, body: unknown, what: string) {
    super(`${what} → ${status} ${code}: ${JSON.stringify(body)}`);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

function base32Decode(secret: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const char of secret.replace(/=+$/, '').replace(/\s/g, '').toUpperCase()) {
    const value = alphabet.indexOf(char);
    if (value < 0) throw new Error('invalid base32 secret');
    bits += value.toString(2).padStart(5, '0');
  }
  const bytes: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s), as SECURITY.md §3.5 specifies. */
export function totp(secret: string, at: number = Date.now()): { code: string; step: number } {
  const step = Math.floor(at / 30_000);
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const hmac = createHmac('sha1', base32Decode(secret)).update(counter).digest();
  const offset = hmac[hmac.length - 1]! & 0x0f;
  const binary = (hmac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return { code: String(binary).padStart(6, '0'), step };
}

// The server rejects a time step that was already used (replay protection), so each user waits
// for a fresh step before the next second-factor check.
// The used steps are kept in a file, so separate processes (seed, test workers) respect them too.
const STEPS_FILE = resolve(import.meta.dirname, '../.state/totp-steps.json');
function readSteps(): Record<string, number> {
  try {
    return existsSync(STEPS_FILE) ? (JSON.parse(readFileSync(STEPS_FILE, 'utf8')) as Record<string, number>) : {};
  } catch {
    return {};
  }
}
export async function freshTotp(secret: string): Promise<string> {
  for (;;) {
    const { code, step } = totp(secret);
    const steps = readSteps();
    if ((steps[secret] ?? -1) < step) {
      steps[secret] = step;
      mkdirSync(dirname(STEPS_FILE), { recursive: true });
      writeFileSync(STEPS_FILE, JSON.stringify(steps));
      return code;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
}

export interface RequestOptions {
  body?: unknown;
  ifMatch?: number;
  idempotencyKey?: string;
  query?: Record<string, string | number | boolean | undefined>;
  allow?: number[];
}

export class Session {
  private cookies = new Map<string, string>();
  companyId?: string;

  /** A session from a Playwright storage state (no new sign-in: sign-ins are rate limited). */
  static fromStorage(path: string, companyId?: string): Session {
    const session = new Session();
    const state = JSON.parse(readFileSync(path, 'utf8')) as { cookies: { name: string; value: string }[] };
    for (const cookie of state.cookies) session.cookies.set(cookie.name, cookie.value);
    session.companyId = companyId;
    return session;
  }

  cookieHeader(): string {
    return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  /** The cookies in Playwright's format, to start a browser already signed in. */
  browserCookies(url: string = APP_ORIGIN) {
    return [...this.cookies.entries()].map(([name, value]) => ({ name, value, url }));
  }

  private store(response: Response) {
    for (const header of response.headers.getSetCookie()) {
      const [pair] = header.split(';');
      const index = pair!.indexOf('=');
      const name = pair!.slice(0, index).trim();
      const value = pair!.slice(index + 1).trim();
      if (/max-age=0/i.test(header) || value === '') this.cookies.delete(name);
      else this.cookies.set(name, value);
    }
  }

  async raw(method: string, path: string, options: RequestOptions = {}): Promise<Response> {
    const unsafe = method !== 'GET';
    if (unsafe && !this.cookies.has('XSRF-TOKEN')) await this.raw('GET', '/api/v1/auth/csrf');
    const url = new URL(path.startsWith('/api') ? path : `/api/v1/companies/${this.companyId}${path}`, API_URL);
    for (const [k, v] of Object.entries(options.query ?? {})) if (v !== undefined) url.searchParams.set(k, String(v));
    const headers: Record<string, string> = { Accept: 'application/json', Cookie: this.cookieHeader() };
    if (unsafe) {
      headers.Origin = APP_ORIGIN;
      headers['X-CSRF-Token'] = this.cookies.get('XSRF-TOKEN') ?? '';
    }
    if (options.body !== undefined) headers['Content-Type'] = method === 'PATCH' ? 'application/merge-patch+json' : 'application/json';
    if (options.ifMatch !== undefined) headers['If-Match'] = `W/"${options.ifMatch}"`;
    if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
    const response = await fetch(url, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      redirect: 'manual',
    });
    this.store(response);
    return response;
  }

  async call<T = any>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    const response = await this.raw(method, path, options);
    const text = await response.text();
    const body = text ? JSON.parse(text) : undefined;
    if (!response.ok && !(options.allow ?? []).includes(response.status)) {
      throw new ApiFailure(response.status, body?.code ?? 'HTTP', body, `${method} ${path}`);
    }
    return body as T;
  }

  get<T = any>(path: string, query?: RequestOptions['query']) {
    return this.call<T>('GET', path, { query });
  }
  post<T = any>(path: string, body?: unknown, options: Omit<RequestOptions, 'body'> = {}) {
    return this.call<T>('POST', path, { ...options, body });
  }
  put<T = any>(path: string, body: unknown, options: Omit<RequestOptions, 'body'> = {}) {
    return this.call<T>('PUT', path, { ...options, body });
  }
  patch<T = any>(path: string, body: unknown, options: Omit<RequestOptions, 'body'> = {}) {
    return this.call<T>('PATCH', path, { ...options, body });
  }
  /** Every row of a list endpoint. */
  async all<T = any>(path: string, query: RequestOptions['query'] = {}): Promise<T[]> {
    const rows: T[] = [];
    let cursor: string | undefined;
    for (;;) {
      const page = await this.get<{ data: T[]; page: { nextCursor?: string; hasMore: boolean } }>(path, { ...query, limit: 200, cursor });
      rows.push(...page.data);
      if (!page.page?.hasMore) return rows;
      cursor = page.page.nextCursor;
    }
  }
}

export interface Credentials {
  email: string;
  password: string;
  totpSecret?: string;
}

/**
 * Signs in: password, then the TOTP code when asked. A user who must enroll MFA does so here and
 * the returned credentials carry the new secret.
 */
export async function login(credentials: Credentials): Promise<{ session: Session; credentials: Credentials }> {
  const session = new Session();
  const response = await session.raw('POST', '/api/v1/auth/login', { body: { email: credentials.email, password: credentials.password } });
  const body = (await response.json()) as any;
  if (response.status === 401 && body.code === 'MFA_REQUIRED') {
    if (!credentials.totpSecret) throw new Error(`${credentials.email} needs a TOTP secret`);
    await session.post('/api/v1/auth/login/mfa', { code: await freshTotp(credentials.totpSecret) });
  } else if (!response.ok) {
    throw new ApiFailure(response.status, body.code, body, `login ${credentials.email}`);
  } else if (body.mfaEnrollmentRequired) {
    const setup = await session.post('/api/v1/me/mfa/totp/setup');
    const secret: string = setup.secret;
    await session.post('/api/v1/me/mfa/totp/confirm', { code: await freshTotp(secret) });
    credentials = { ...credentials, totpSecret: secret };
  }
  return { session, credentials };
}

/** The token of the latest invitation or reset email sent to an address (Mailpit). */
export async function mailToken(email: string, path: '/accept-invitation' | '/reset-password'): Promise<string> {
  for (let attempt = 0; attempt < 30; attempt++) {
    const search: any = await (await fetch(`${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`)).json();
    for (const message of search.messages ?? []) {
      const full: any = await (await fetch(`${MAILPIT_URL}/api/v1/message/${message.ID}`)).json();
      const match = new RegExp(`${path}#token=([A-Za-z0-9_\\-.~%]+)`).exec(`${full.Text ?? ''} ${full.HTML ?? ''}`);
      if (match) return decodeURIComponent(match[1]!);
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no ${path} email for ${email}`);
}
