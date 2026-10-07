import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, companyApi, download, setAuthHandlers } from './client';
import { ApiError, pointerToPath } from './errors';

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

describe('API client', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    document.cookie = 'XSRF-TOKEN=token-1; path=/';
  });
  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  it('sends the CSRF token, If-Match and Idempotency-Key on unsafe requests', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { id: 'o1', status: 'CONFIRMED' }));
    const c = companyApi('c1');
    await c.post('/sales-orders/{orderId}/confirm', { orderId: 'o1' }, { ifMatch: 3, idempotencyKey: 'key-12345', body: {} });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/api/v1/companies/c1/sales-orders/o1/confirm');
    expect(init?.method).toBe('POST');
    expect(init?.headers).toMatchObject({ 'X-CSRF-Token': 'token-1', 'If-Match': 'W/"3"', 'Idempotency-Key': 'key-12345' });
  });

  it('sends PATCH as JSON merge patch', async () => {
    fetchMock.mockResolvedValueOnce(json(200, {}));
    await companyApi('c1').patch('/branches/{branchId}', { branchId: 'b1' }, { body: { name: null }, ifMatch: 1 });
    expect(fetchMock.mock.calls[0]![1]?.headers).toMatchObject({ 'Content-Type': 'application/merge-patch+json' });
    expect(fetchMock.mock.calls[0]![1]?.body).toBe('{"name":null}');
  });

  it('turns problem documents into ApiError', async () => {
    fetchMock.mockResolvedValueOnce(
      json(422, { status: 422, code: 'VALIDATION_FAILED', title: 'Invalid', errors: [{ pointer: '/lines/0/quantity', code: 'POSITIVE', message: 'must be positive' }] }),
    );
    const error = await api.get('/api/v1/me', {}).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('VALIDATION_FAILED');
    expect(pointerToPath((error as ApiError).fieldErrors[0]!.pointer!)).toBe('lines.0.quantity');
  });

  it('asks for step-up re-authentication and retries once with the same key', async () => {
    const onStepUp = vi.fn().mockResolvedValue(true);
    const restore = setAuthHandlers({ onStepUp });
    fetchMock
      .mockResolvedValueOnce(json(403, { status: 403, code: 'REAUTHENTICATION_REQUIRED', title: 'Step-up' }))
      .mockResolvedValueOnce(json(200, { employeeId: 'e1' }));
    const result = await companyApi('c1').post('/employees/{employeeId}/reveal', { employeeId: 'e1' }, { idempotencyKey: 'same-key-1' });
    expect(onStepUp).toHaveBeenCalledOnce();
    expect(result).toEqual({ employeeId: 'e1' });
    expect(fetchMock.mock.calls[1]![1]?.headers).toMatchObject({ 'Idempotency-Key': 'same-key-1' });
    restore();
  });

  it('reports an expired session on 401, unless the call is anonymous', async () => {
    const onUnauthenticated = vi.fn();
    const restore = setAuthHandlers({ onUnauthenticated });
    fetchMock.mockImplementation(() => Promise.resolve(json(401, { status: 401, code: 'UNAUTHENTICATED', title: 'Unauthenticated' })));
    await api.get('/api/v1/companies', {}).catch(() => undefined);
    expect(onUnauthenticated).toHaveBeenCalledOnce();
    await api.get('/api/v1/me', {}, { anonymous: true }).catch(() => undefined);
    expect(onUnauthenticated).toHaveBeenCalledOnce();
    restore();
  });

  it.each([
    ['an RFC 5987 name', "attachment; filename*=UTF-8''Payslip%20M%C3%A4rz.pdf", 'Payslip März.pdf'],
    ['a plain name with a percent sign', 'attachment; filename="100% report.csv"', '100% report.csv'],
    ['a malformed encoded name', "attachment; filename*=UTF-8''bad%E0%A4%A.csv", 'fallback.csv'],
  ])('downloads files under %s', async (_, disposition, expected) => {
    fetchMock.mockResolvedValueOnce(new Response('a,b', { status: 200, headers: { 'Content-Disposition': disposition } }));
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }));
    const names: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      names.push(this.download);
    });
    await download('/api/v1/file', 'fallback.csv');
    expect(names).toEqual([expected]);
    click.mockRestore();
  });

  it('reports network failures as a NETWORK_ERROR problem', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    const error = (await api.get('/api/v1/me', {}).catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('NETWORK_ERROR');
  });
});
