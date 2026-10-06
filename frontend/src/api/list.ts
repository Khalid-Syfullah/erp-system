// List query parameters (API.md §8): keyset pagination, allowlisted filters and sort, quick search.
import type { Query, QueryValue } from './client';

export interface PageInfo {
  limit?: number;
  nextCursor?: string | null;
  hasMore?: boolean;
}

export interface Page<T> {
  data?: T[];
  page?: PageInfo;
  meta?: { totalCount?: number | null };
}

export type FilterOperator = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'like' | 'isNull';

/**
 * Filters keyed by `field` (equality) or `field.operator`: `{ status: 'POSTED', 'orderDate.gte': '2026-01-01',
 * 'status.in': ['DRAFT', 'CONFIRMED'] }` → `filter[status]=POSTED&filter[orderDate][gte]=…&filter[status][in]=DRAFT,CONFIRMED`.
 */
export type Filters = Record<string, QueryValue>;

export interface ListParams {
  limit?: number;
  cursor?: string | null;
  sort?: string | null;
  q?: string | null;
  filters?: Filters;
  includeTotal?: boolean;
  /** Endpoint-specific parameters (asOf, year, from/to …). */
  params?: Query;
}

export function filterKey(key: string): string {
  const dot = key.indexOf('.');
  return dot < 0 ? `filter[${key}]` : `filter[${key.slice(0, dot)}][${key.slice(dot + 1)}]`;
}

export function listQuery(params: ListParams): Query {
  const query: Query = { ...(params.params ?? {}) };
  if (params.limit) query.limit = params.limit;
  if (params.cursor) query.cursor = params.cursor;
  if (params.sort) query.sort = params.sort;
  const q = params.q?.trim();
  if (q && q.length >= 2) query.q = q;
  if (params.includeTotal) query.includeTotal = true;
  for (const [key, value] of Object.entries(params.filters ?? {})) {
    if (value === undefined || value === null || value === '' || (Array.isArray(value) && value.length === 0)) continue;
    query[filterKey(key)] = value;
  }
  return query;
}

/** Sort parameter of one column: `field` or `-field`. */
export function sortParam(field: string, descending: boolean): string {
  return descending ? `-${field}` : field;
}

export function parseSort(sort: string | null | undefined): { field: string; descending: boolean } | null {
  if (!sort) return null;
  const first = sort.split(',')[0]!.trim();
  if (!first) return null;
  return first.startsWith('-') ? { field: first.slice(1), descending: true } : { field: first, descending: false };
}
