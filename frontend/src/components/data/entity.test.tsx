import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, renderInCompany } from '@/test/render';
import { entities } from './entities';
import { EntityName } from './entity';

const variant = (id: string, sku: string) => ({ id, sku, name: `Item ${sku}`, status: 'ACTIVE' });

describe('EntityName', () => {
  const fetchMock = vi.fn<typeof fetch>();
  beforeEach(() => vi.stubGlobal('fetch', fetchMock));
  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  it('fetches the records shown together in one request, not one per cell', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { data: [variant('v1', 'A-1'), variant('v2', 'B-2')], page: { hasMore: false } }));
    renderInCompany(
      <ul>
        {['v1', 'v2', 'v1', 'v3'].map((id, i) => (
          <li key={i}>
            <EntityName source={entities.variant} id={id} />
          </li>
        ))}
      </ul>,
      { permissions: ['inventory.product.read'] },
    );

    expect(await screen.findAllByText('A-1 — Item A-1')).toHaveLength(2);
    expect(screen.getByText('B-2 — Item B-2')).toBeInTheDocument();
    // An ID the server does not return (unknown or outside the user's scope) stays shortened.
    expect(await screen.findByText('…v3')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const url = new URL(String(fetchMock.mock.calls[0]![0]), 'http://localhost');
    expect(url.pathname).toBe('/api/v1/companies/c1/variants');
    expect(url.searchParams.get('filter[id][in]')?.split(',').sort()).toEqual(['v1', 'v2', 'v3']);
    expect(url.searchParams.get('limit')).toBe('3');
  });

  it('shows shortened IDs without the read permission and asks nothing', async () => {
    renderInCompany(<EntityName source={entities.variant} id="variant-123456" />, { permissions: [] });
    expect(await screen.findByText('…123456')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
