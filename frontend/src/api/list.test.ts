import { describe, expect, it } from 'vitest';
import { filterKey, listQuery, parseSort } from './list';
import { toSearchParams } from './client';

describe('list queries (API.md §8)', () => {
  it('builds filters, sort, cursor and search', () => {
    const query = listQuery({
      limit: 50,
      cursor: 'abc',
      sort: '-createdAt',
      q: ' wid ',
      filters: { status: 'POSTED', 'orderDate.gte': '2026-01-01', 'status.in': ['DRAFT', 'CONFIRMED'], empty: '', none: undefined },
    });
    expect(toSearchParams(query)).toBe(
      '?limit=50&cursor=abc&sort=-createdAt&q=wid&filter%5Bstatus%5D=POSTED&filter%5BorderDate%5D%5Bgte%5D=2026-01-01&filter%5Bstatus%5D%5Bin%5D=DRAFT%2CCONFIRMED',
    );
  });

  it('ignores searches shorter than two characters', () => {
    expect(listQuery({ q: 'a' }).q).toBeUndefined();
  });

  it('maps filter keys and parses sort', () => {
    expect(filterKey('number.like')).toBe('filter[number][like]');
    expect(parseSort('-total,code')).toEqual({ field: 'total', descending: true });
    expect(parseSort(null)).toBeNull();
  });
});
