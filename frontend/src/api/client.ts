// The API client (API.md §9–§12, SECURITY.md §3.3, §10.3): a thin, typed layer over fetch.
//
// - Paths and bodies are typed from the OpenAPI document (src/api/schema.d.ts).
// - Unsafe requests carry the CSRF token from the XSRF-TOKEN cookie (bootstrapped from /auth/csrf).
// - `ifMatch` sends `If-Match: W/"<version>"`; `idempotencyKey` sends `Idempotency-Key`.
// - Failures become ApiError with the server's problem document.
// - 401 on a signed-in call reports an expired session; 403 REAUTHENTICATION_REQUIRED asks the
//   registered step-up handler for the password and retries once with the same Idempotency-Key.
import type { components, paths } from './schema';
import { ApiError, syntheticProblem, type Problem } from './errors';

export type Schemas = components['schemas'];
export type HttpMethod = 'get' | 'post' | 'put' | 'patch' | 'delete';
type PathKey = keyof paths;

export type PathsFor<M extends HttpMethod> = {
  [P in PathKey]: undefined extends paths[P][M] ? never : P;
}[PathKey];

type Operation<P extends PathKey, M extends HttpMethod> = NonNullable<paths[P][M]>;

type PathParamsOf<O> = O extends { parameters: { path: infer X } } ? (X extends Record<string, unknown> ? X : never) : never;

type JsonContent<C> = C extends { content: { 'application/json': infer B } }
  ? B
  : C extends { content: { 'application/merge-patch+json': unknown } }
    ? Record<string, unknown>
    : never;

export type BodyOf<P extends PathKey, M extends HttpMethod> =
  Operation<P, M> extends { requestBody?: infer R } ? JsonContent<NonNullable<R>> : never;

type Untyped<T> = T extends Record<string, never> ? (keyof T extends never ? unknown : T) : T;
type SuccessContent<R> = R extends { 200: infer S }
  ? S
  : R extends { 201: infer S }
    ? S
    : R extends { 202: infer S }
      ? S
      : never;

export type ResponseOf<P extends PathKey, M extends HttpMethod> =
  Operation<P, M> extends { responses: infer R }
    ? SuccessContent<R> extends { content: { 'application/json': infer T } }
      ? Untyped<T>
      : SuccessContent<R> extends { content: { '*/*': infer T } }
        ? Untyped<T>
        : void
    : never;

export type QueryValue = string | number | boolean | null | undefined | readonly string[];
export type Query = Record<string, QueryValue>;

export interface RequestOptions {
  query?: Query;
  body?: unknown;
  /** The resource version for If-Match (API.md §9). */
  ifMatch?: number | null;
  /** One key per user intent, reused on retries (API.md §10). */
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Do not report a 401 as an expired session (sign-in pages, the session probe). */
  anonymous?: boolean;
}

export interface AuthHandlers {
  /** A signed-in call answered 401: the session expired or was revoked. */
  onUnauthenticated?: () => void;
  /** 403 REAUTHENTICATION_REQUIRED: confirm the password; resolves true when the user did. */
  onStepUp?: () => Promise<boolean>;
  /** 403 MFA_ENROLLMENT_REQUIRED: the session may only enroll TOTP. */
  onMfaEnrollmentRequired?: () => void;
}

const handlers: AuthHandlers = {};

export function setAuthHandlers(next: AuthHandlers): () => void {
  Object.assign(handlers, next);
  return () => {
    for (const key of Object.keys(next) as (keyof AuthHandlers)[]) {
      if (handlers[key] === next[key]) delete handlers[key];
    }
  };
}

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-CSRF-Token';
const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function readCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined;
  for (const part of document.cookie.split(';')) {
    const [key, ...value] = part.trim().split('=');
    if (key === name) return decodeURIComponent(value.join('='));
  }
  return undefined;
}

let csrfBootstrap: Promise<void> | null = null;

/** Issues the CSRF cookie (GET /api/v1/auth/csrf) once, if the browser has none. */
export async function ensureCsrfToken(force = false): Promise<void> {
  if (!force && readCookie(CSRF_COOKIE)) return;
  csrfBootstrap ??= fetch('/api/v1/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
    .then(() => undefined)
    .finally(() => {
      csrfBootstrap = null;
    });
  await csrfBootstrap;
}

/** Serializes list and report parameters. Arrays become comma-separated values (`filter[x][in]`). */
export function toSearchParams(query: Query | undefined): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      if (value.length > 0) params.set(key, value.join(','));
    } else {
      params.set(key, String(value));
    }
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

/** Replaces `{name}` placeholders with URL-encoded values. */
export function fillPath(template: string, params: Record<string, unknown> = {}): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => {
    const value = params[name];
    if (value === undefined || value === null || value === '') {
      throw new Error(`Missing path parameter ${name} for ${template}`);
    }
    return encodeURIComponent(String(value));
  });
}

async function readProblem(response: Response): Promise<Problem> {
  const type = response.headers.get('Content-Type') ?? '';
  if (type.includes('json')) {
    try {
      const body = (await response.json()) as Partial<Problem>;
      if (body && typeof body.code === 'string') {
        return { ...body, status: body.status ?? response.status, code: body.code };
      }
    } catch {
      // fall through to a synthetic problem
    }
  }
  return syntheticProblem(response.status, response.status >= 500 ? 'INTERNAL_ERROR' : 'HTTP_' + response.status);
}

/** Sends a request and returns the raw response after error handling (for files). */
export async function send(method: string, url: string, options: RequestOptions = {}, retried = false): Promise<Response> {
  const upper = method.toUpperCase();
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };
  const isForm = typeof FormData !== 'undefined' && options.body instanceof FormData;
  if (options.body !== undefined && !isForm) {
    headers['Content-Type'] = upper === 'PATCH' ? 'application/merge-patch+json' : 'application/json';
  }
  if (options.ifMatch !== undefined && options.ifMatch !== null) headers['If-Match'] = `W/"${options.ifMatch}"`;
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
  if (UNSAFE.has(upper)) {
    await ensureCsrfToken();
    const token = readCookie(CSRF_COOKIE);
    if (token) headers[CSRF_HEADER] = token;
  }

  let response: Response;
  try {
    response = await fetch(url + toSearchParams(options.query), {
      method: upper,
      headers,
      credentials: 'same-origin',
      body:
        options.body === undefined ? undefined : isForm ? (options.body as FormData) : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(syntheticProblem(0, 'NETWORK_ERROR'));
  }
  if (response.ok) return response;

  const problem = await readProblem(response);
  if (!retried && problem.code === 'CSRF_INVALID') {
    await ensureCsrfToken(true);
    return send(method, url, options, true);
  }
  if (!retried && problem.code === 'REAUTHENTICATION_REQUIRED' && handlers.onStepUp) {
    if (await handlers.onStepUp()) return send(method, url, options, true);
  }
  if (problem.code === 'MFA_ENROLLMENT_REQUIRED') handlers.onMfaEnrollmentRequired?.();
  if (response.status === 401 && !options.anonymous) handlers.onUnauthenticated?.();
  throw new ApiError(problem);
}

/** Sends a request and parses the JSON body (undefined for 204 and empty bodies). */
export async function request<T>(method: string, url: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(method, url, options);
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** The version in an `ETag: W/"<version>"` header (API.md §9), for resources whose body has none. */
export function versionOf(response: Response): number | null {
  const match = /^(?:W\/)?"(\d+)"$/.exec(response.headers.get('ETag') ?? '');
  return match ? Number(match[1]) : null;
}

/** A GET that also returns the representation's version from its ETag. */
export async function requestVersioned<T>(url: string, options: RequestOptions = {}): Promise<{ data: T; version: number | null }> {
  const response = await send('GET', url, options);
  return { data: (await response.json()) as T, version: versionOf(response) };
}

type Args<P extends PathKey, M extends HttpMethod> = [PathParamsOf<Operation<P, M>>] extends [never]
  ? [params?: Record<string, never>, options?: RequestOptions]
  : [params: PathParamsOf<Operation<P, M>>, options?: RequestOptions];

/** Typed calls against the full API paths: `api.get('/api/v1/me')`. */
export const api = {
  get<P extends PathsFor<'get'>>(path: P, ...[params, options]: Args<P, 'get'>) {
    return request<ResponseOf<P, 'get'>>('GET', fillPath(path, params), options);
  },
  post<P extends PathsFor<'post'>>(path: P, ...[params, options]: Args<P, 'post'>) {
    return request<ResponseOf<P, 'post'>>('POST', fillPath(path, params), options);
  },
  put<P extends PathsFor<'put'>>(path: P, ...[params, options]: Args<P, 'put'>) {
    return request<ResponseOf<P, 'put'>>('PUT', fillPath(path, params), options);
  },
  patch<P extends PathsFor<'patch'>>(path: P, ...[params, options]: Args<P, 'patch'>) {
    return request<ResponseOf<P, 'patch'>>('PATCH', fillPath(path, params), options);
  },
  delete<P extends PathsFor<'delete'>>(path: P, ...[params, options]: Args<P, 'delete'>) {
    return request<ResponseOf<P, 'delete'>>('DELETE', fillPath(path, params), options);
  },
};

// Company-scoped paths, written without the `/api/v1/companies/{companyId}` prefix.
const COMPANY_PREFIX = '/api/v1/companies/{companyId}';
type Prefix = typeof COMPANY_PREFIX;
export type CompanyPath<M extends HttpMethod> = PathsFor<M> extends infer P
  ? P extends `${Prefix}${infer Rest}`
    ? Rest
    : never
  : never;
type Full<R extends string> = `${Prefix}${R}` & PathKey;
type CompanyArgs<R extends string, M extends HttpMethod> = Omit<PathParamsOf<Operation<Full<R>, M>>, 'companyId'>;
type CArgs<R extends string, M extends HttpMethod> = keyof CompanyArgs<R, M> extends never
  ? [params?: Record<string, never> | null, options?: RequestOptions]
  : [params: CompanyArgs<R, M>, options?: RequestOptions];

export type CompanyResponse<R extends string, M extends HttpMethod> = ResponseOf<Full<R>, M>;
export type CompanyBody<R extends string, M extends HttpMethod> = BodyOf<Full<R>, M>;

export interface CompanyApi {
  companyId: string;
  url(path: string, params?: Record<string, unknown> | null): string;
  get<R extends CompanyPath<'get'>>(path: R, ...args: CArgs<R, 'get'>): Promise<CompanyResponse<R, 'get'>>;
  post<R extends CompanyPath<'post'>>(path: R, ...args: CArgs<R, 'post'>): Promise<CompanyResponse<R, 'post'>>;
  put<R extends CompanyPath<'put'>>(path: R, ...args: CArgs<R, 'put'>): Promise<CompanyResponse<R, 'put'>>;
  patch<R extends CompanyPath<'patch'>>(path: R, ...args: CArgs<R, 'patch'>): Promise<CompanyResponse<R, 'patch'>>;
  delete<R extends CompanyPath<'delete'>>(path: R, ...args: CArgs<R, 'delete'>): Promise<CompanyResponse<R, 'delete'>>;
  /** GET with the version from the ETag header (settings singletons). */
  getVersioned<R extends CompanyPath<'get'>>(
    path: R,
    ...args: CArgs<R, 'get'>
  ): Promise<{ data: CompanyResponse<R, 'get'>; version: number | null }>;
}

/** Typed calls against one company's resources: `companyApi(id).get('/sales-orders/{orderId}', { orderId })`. */
export function companyApi(companyId: string): CompanyApi {
  const url = (path: string, params?: Record<string, unknown> | null) =>
    fillPath(COMPANY_PREFIX + path, { ...(params ?? {}), companyId });
  const call =
    (method: string) =>
    (path: string, params?: Record<string, unknown> | null, options?: RequestOptions) =>
      request(method, url(path, params), options);
  return {
    companyId,
    url,
    get: call('GET'),
    post: call('POST'),
    put: call('PUT'),
    patch: call('PATCH'),
    delete: call('DELETE'),
    getVersioned: (path: string, params?: Record<string, unknown> | null, options?: RequestOptions) =>
      requestVersioned(url(path, params), options),
  } as CompanyApi;
}

/** The file name of a Content-Disposition header: RFC 5987 `filename*` (percent-encoded) or plain `filename`. */
function fileName(encoded: string | undefined, plain: string | undefined): string | undefined {
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      return undefined;
    }
  }
  return plain;
}

/** Downloads a file response (exports, payslips, bank files) and hands it to the browser. */
export async function download(url: string, fallbackName: string, options: RequestOptions = {}): Promise<void> {
  const response = await send('GET', url, options);
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(disposition);
  const name = fileName(match?.[1], match?.[2]) ?? fallbackName;
  const href = URL.createObjectURL(blob);
  try {
    const link = document.createElement('a');
    link.href = href;
    link.download = name;
    document.body.append(link);
    link.click();
    link.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(href), 1000);
  }
}
