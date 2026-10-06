import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, renderInCompany } from '@/test/render';
import { DataTable, type Column } from './data-table';

interface Row {
  id: string;
  code: string;
}

const columns: Column<Row>[] = [
  { id: 'code', header: 'Code', sortKey: 'code', cell: (r) => <a href={`#${r.id}`} data-row-link>{r.code}</a> },
];

describe('DataTable', () => {
  const fetchMock = vi.fn<typeof fetch>();
  beforeEach(() => vi.stubGlobal('fetch', fetchMock));
  afterEach(() => {
    fetchMock.mockReset();
    vi.unstubAllGlobals();
  });

  const table = (id: string) => (
    <DataTable<Row>
      id={id}
      fetchPage={(api, query, signal) => api.get('/branches', null, { query, signal }) as never}
      columns={columns}
      rowKey={(r) => r.id}
      defaultSort="code"
      filters={[{ kind: 'boolean', key: 'isActive', label: 'Active' }]}
    />
  );

  it('loads pages with the cursor and sorts on the server', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, { data: [{ id: '1', code: 'A' }], page: { hasMore: true, nextCursor: 'cur-2' } }))
      .mockResolvedValueOnce(jsonResponse(200, { data: [{ id: '2', code: 'B' }], page: { hasMore: false } }))
      .mockImplementation(() => Promise.resolve(jsonResponse(200, { data: [{ id: '3', code: 'Z' }], page: { hasMore: false } })));
    const user = userEvent.setup();
    renderInCompany(table('t1'));

    expect(await screen.findByText('A')).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0]![0])).toContain('sort=code');

    await user.click(screen.getByRole('button', { name: /next/i }));
    expect(await screen.findByText('B')).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[1]![0])).toContain('cursor=cur-2');
    expect(screen.getByText('Page 2')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /code/i }));
    await waitFor(() => expect(String(fetchMock.mock.lastCall![0])).toContain('sort=-code'));
    expect(await screen.findByRole('columnheader', { name: /code/i })).toHaveAttribute('aria-sort', 'descending');
    expect(await screen.findByText('Page 1')).toBeInTheDocument();
  });

  it('shows the empty state and the server problem', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { data: [], page: { hasMore: false } }));
    renderInCompany(table('t2'));
    expect(await screen.findByText('Nothing here yet')).toBeInTheDocument();
  });

  it('renders not-found problems as such', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(404, { status: 404, code: 'NOT_FOUND', title: 'Not found' })));
    renderInCompany(table('t3'));
    expect(await screen.findByText('Not found')).toBeInTheDocument();
  });

  it('moves between row links with the arrow keys', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { data: [{ id: '1', code: 'A' }, { id: '2', code: 'B' }], page: { hasMore: false } }),
    );
    const user = userEvent.setup();
    renderInCompany(table('t4'));
    const rows = await screen.findAllByRole('row');
    within(rows[1]!).getByText('A').focus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByText('B')).toHaveFocus();
  });
});
