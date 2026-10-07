import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight, RefreshCw, Search, X } from 'lucide-react';
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import type { CompanyApi, Query, QueryValue } from '@/api/client';
import { useCompanyList } from '@/api/hooks';
import { parseSort, sortParam, type Filters, type Page } from '@/api/list';
import { useOptionalCompany } from '@/auth/company';
import { EmptyState, ErrorState, SkeletonRows } from '@/components/feedback/states';
import { DateInput } from '@/components/form/inputs';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { t, enumLabel } from '@/i18n';
import { useDebounced } from '@/lib/use-debounced';
import { cn } from '@/lib/utils';
import { EntityPicker } from './entity';
import type { EntitySource } from './entities';

export interface Column<T> {
  id: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  /** The server's sort field; only allowlisted fields are sortable (API.md §8.3). */
  sortKey?: string;
  align?: 'left' | 'right' | 'center';
  className?: string;
  /** Hide on narrow screens. */
  hideBelow?: 'sm' | 'md' | 'lg';
  /** Footer cell (totals). */
  footer?: ReactNode;
}

export type FilterDef =
  | { kind: 'select'; key: string; label: string; options: { value: string; label: string }[] }
  | { kind: 'enum'; key: string; label: string; values: string[] }
  | { kind: 'boolean'; key: string; label: string; trueLabel?: string; falseLabel?: string }
  | { kind: 'dateRange'; field: string; label: string }
  | { kind: 'date'; key: string; label: string }
  | { kind: 'text'; key: string; label: string }
  | { kind: 'entity'; key: string; label: string; source: EntitySource<unknown> };

interface TableState {
  q: string;
  filters: Filters;
  sort: string | null;
  limit: number;
}

// The list state survives navigating to a record and back (per company and table, in memory only).
const remembered = new Map<string, TableState>();

export interface DataTableProps<T> {
  /** Identifies the list (query key and remembered state). */
  id: string;
  fetchPage: (api: CompanyApi, query: Query, signal: AbortSignal) => Promise<Page<T>>;
  columns: Column<T>[];
  rowKey: (row: T) => string;
  filters?: FilterDef[];
  /** Fixed filters the user does not see (e.g. documentType). */
  fixedFilters?: Filters;
  /** Endpoint parameters outside filter[] (asOf, from, to …). */
  params?: Query;
  defaultSort?: string;
  searchable?: boolean;
  searchPlaceholder?: string;
  toolbar?: ReactNode;
  emptyTitle?: string;
  emptyAction?: ReactNode;
  /** Opens a row (mouse click anywhere on it). Keyboard users use the row's link. */
  onRowOpen?: (row: T) => void;
  caption?: string;
  enabled?: boolean;
  pageSizes?: number[];
  dense?: boolean;
  /** Refreshes the page every few seconds while the condition holds (e.g. jobs still running). */
  pollWhile?: (rows: T[]) => boolean;
}

const ALL = '__all__';

export function DataTable<T>({
  id,
  fetchPage,
  columns,
  rowKey,
  filters = [],
  fixedFilters,
  params,
  defaultSort,
  searchable = true,
  searchPlaceholder,
  toolbar,
  emptyTitle,
  emptyAction,
  onRowOpen,
  caption,
  enabled = true,
  pageSizes = [25, 50, 100],
  dense,
  pollWhile,
}: DataTableProps<T>) {
  const companyId = useOptionalCompany()?.companyId ?? 'global';
  const memoryKey = `${companyId}:${id}`;
  const [state, setState] = useState<TableState>(
    () => remembered.get(memoryKey) ?? { q: '', filters: {}, sort: defaultSort ?? null, limit: pageSizes[0] ?? 25 },
  );
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const q = useDebounced(state.q, 300);

  useEffect(() => {
    remembered.set(memoryKey, state);
  }, [memoryKey, state]);

  // Any change of the query starts again at the first page (cursors belong to one query).
  const signature = JSON.stringify([q, state.filters, state.sort, state.limit, fixedFilters, params]);
  const lastSignature = useRef(signature);
  useEffect(() => {
    if (lastSignature.current !== signature) {
      lastSignature.current = signature;
      setCursors([null]);
    }
  }, [signature]);

  const cursor = cursors[cursors.length - 1] ?? null;
  const query = useCompanyList<T>(
    id,
    fetchPage,
    {
      limit: state.limit,
      cursor,
      sort: state.sort,
      q: searchable ? q : undefined,
      filters: { ...state.filters, ...fixedFilters },
      params,
    },
    { enabled, pollWhile: pollWhile ? (page) => pollWhile(page.data ?? []) : undefined },
  );

  const rows = query.data?.data ?? [];
  const page = query.data?.page;
  const filtered = Object.values(state.filters).some((v) => v !== undefined && v !== null && v !== '') || q.trim().length >= 2;
  const currentSort = parseSort(state.sort);

  const setFilter = (key: string, value: QueryValue) =>
    setState((s) => ({ ...s, filters: { ...s.filters, [key]: value ?? undefined } }));
  const toggleSort = (field: string) =>
    setState((s) => {
      const current = parseSort(s.sort);
      const descending = current?.field === field ? !current.descending : false;
      return { ...s, sort: sortParam(field, descending) };
    });

  const body = useRef<HTMLTableSectionElement>(null);
  const onBodyKeyDown = (event: KeyboardEvent<HTMLTableSectionElement>) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const links = Array.from(body.current?.querySelectorAll<HTMLElement>('tr [data-row-link]') ?? []);
    const index = links.findIndex((el) => el === document.activeElement);
    if (index < 0) return;
    event.preventDefault();
    links[Math.min(links.length - 1, Math.max(0, index + (event.key === 'ArrowDown' ? 1 : -1)))]?.focus();
  };

  const hide = { sm: 'hidden sm:table-cell', md: 'hidden md:table-cell', lg: 'hidden lg:table-cell' };
  const hasFooter = columns.some((c) => c.footer !== undefined);

  return (
    <div className="rounded-lg border bg-card">
      {searchable || filters.length > 0 || toolbar ? (
        <div className="flex flex-wrap items-end gap-2 border-b p-3">
          {searchable ? (
            <div className="relative w-full sm:w-64">
              <Search className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground" aria-hidden />
              <Input
                type="search"
                value={state.q}
                onChange={(e) => setState((s) => ({ ...s, q: e.target.value }))}
                placeholder={searchPlaceholder ?? t('common.searchPlaceholder')}
                aria-label={t('common.search')}
                className="pl-8"
              />
            </div>
          ) : null}
          {filters.map((filter) => (
            <FilterControl key={'field' in filter ? filter.field : filter.key} filter={filter} values={state.filters} setFilter={setFilter} />
          ))}
          {filtered ? (
            <Button variant="ghost" size="sm" onClick={() => setState((s) => ({ ...s, q: '', filters: {} }))}>
              <X aria-hidden />
              {t('common.clearFilters')}
            </Button>
          ) : null}
          <div className="ml-auto flex flex-wrap items-center gap-2">
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => query.refetch()}
              aria-label={t('common.refresh')}
              disabled={query.isFetching}
            >
              <RefreshCw className={cn(query.isFetching && 'animate-spin')} aria-hidden />
            </Button>
            {toolbar}
          </div>
        </div>
      ) : null}

      {query.isError && !query.data ? (
        <ErrorState error={query.error} onRetry={() => query.refetch()} />
      ) : query.isLoading ? (
        <SkeletonRows columns={Math.min(columns.length, 6)} />
      ) : rows.length === 0 ? (
        <EmptyState filtered={filtered} title={filtered ? undefined : emptyTitle} action={filtered ? undefined : emptyAction} />
      ) : (
        <div className="overflow-x-auto" aria-busy={query.isFetching || undefined}>
          <Table>
            {caption ? <caption className="sr-only">{caption}</caption> : null}
            <TableHeader>
              <TableRow>
                {columns.map((column) => {
                  const sorted = column.sortKey && currentSort?.field === column.sortKey ? currentSort : null;
                  return (
                    <TableHead
                      key={column.id}
                      scope="col"
                      aria-sort={sorted ? (sorted.descending ? 'descending' : 'ascending') : undefined}
                      className={cn(
                        column.align === 'right' && 'text-right',
                        column.align === 'center' && 'text-center',
                        column.hideBelow && hide[column.hideBelow],
                        column.className,
                      )}
                    >
                      {column.sortKey ? (
                        <button
                          type="button"
                          onClick={() => toggleSort(column.sortKey!)}
                          className={cn(
                            'inline-flex items-center gap-1 rounded hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                            column.align === 'right' && 'flex-row-reverse',
                          )}
                        >
                          {column.header}
                          {sorted ? (
                            sorted.descending ? (
                              <ArrowDown className="size-3.5" aria-hidden />
                            ) : (
                              <ArrowUp className="size-3.5" aria-hidden />
                            )
                          ) : (
                            <ArrowUpDown className="size-3.5 opacity-40" aria-hidden />
                          )}
                        </button>
                      ) : (
                        column.header
                      )}
                    </TableHead>
                  );
                })}
              </TableRow>
            </TableHeader>
            <TableBody ref={body} onKeyDown={onBodyKeyDown}>
              {rows.map((row) => (
                <TableRow
                  key={rowKey(row)}
                  className={cn(onRowOpen && 'cursor-pointer')}
                  onClick={(event) => {
                    if (!onRowOpen) return;
                    const target = event.target as HTMLElement;
                    if (target.closest('a,button,input,[role="combobox"],[role="checkbox"]')) return;
                    onRowOpen(row);
                  }}
                >
                  {columns.map((column) => (
                    <TableCell
                      key={column.id}
                      className={cn(
                        dense ? 'py-1' : 'py-2',
                        column.align === 'right' && 'text-right tabular',
                        column.align === 'center' && 'text-center',
                        column.hideBelow && hide[column.hideBelow],
                        column.className,
                      )}
                    >
                      {column.cell(row)}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
            {hasFooter ? (
              <tfoot className="border-t bg-muted/40 font-medium">
                <TableRow>
                  {columns.map((column) => (
                    <TableCell
                      key={column.id}
                      className={cn(column.align === 'right' && 'text-right tabular', column.hideBelow && hide[column.hideBelow])}
                    >
                      {column.footer}
                    </TableCell>
                  ))}
                </TableRow>
              </tfoot>
            ) : null}
          </Table>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2 border-t px-3 py-2 text-sm">
        <div className="flex items-center gap-2 text-muted-foreground">
          <span>{t('common.rowsPerPage')}</span>
          <Select value={String(state.limit)} onValueChange={(v) => setState((s) => ({ ...s, limit: Number(v) }))}>
            <SelectTrigger size="sm" className="w-20" aria-label={t('common.rowsPerPage')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {pageSizes.map((size) => (
                <SelectItem key={size} value={String(size)}>
                  {size}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <nav className="flex items-center gap-2" aria-label={t('common.pagination')}>
          <span className="text-muted-foreground" aria-live="polite">
            {t('common.pageOf', { page: cursors.length })}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setCursors((c) => c.slice(0, -1))}
            disabled={cursors.length <= 1 || query.isFetching}
          >
            <ChevronLeft aria-hidden />
            {t('common.previous')}
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => page?.nextCursor && setCursors((c) => [...c, page.nextCursor!])}
            disabled={!page?.hasMore || !page.nextCursor || query.isFetching}
          >
            {t('common.next')}
            <ChevronRight aria-hidden />
          </Button>
        </nav>
      </div>
    </div>
  );
}

function FilterControl({
  filter,
  values,
  setFilter,
}: {
  filter: FilterDef;
  values: Filters;
  setFilter: (key: string, value: QueryValue) => void;
}) {
  switch (filter.kind) {
    case 'select':
    case 'enum': {
      const options =
        filter.kind === 'enum' ? filter.values.map((v) => ({ value: v, label: enumLabel(v) })) : filter.options;
      const value = values[filter.key];
      return (
        <div className="w-full space-y-1 sm:w-44">
          <span className="text-xs text-muted-foreground" id={`f-${filter.key}`}>
            {filter.label}
          </span>
          <Select value={typeof value === 'string' && value ? value : ALL} onValueChange={(v) => setFilter(filter.key, v === ALL ? undefined : v)}>
            <SelectTrigger className="w-full" aria-labelledby={`f-${filter.key}`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('common.all')}</SelectItem>
              {options.map((o) => (
                <SelectItem key={o.value} value={o.value}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      );
    }
    case 'boolean': {
      const value = values[filter.key];
      return (
        <div className="w-full space-y-1 sm:w-36">
          <span className="text-xs text-muted-foreground" id={`f-${filter.key}`}>
            {filter.label}
          </span>
          <Select
            value={value === 'true' ? 'true' : value === 'false' ? 'false' : ALL}
            onValueChange={(v) => setFilter(filter.key, v === ALL ? undefined : v)}
          >
            <SelectTrigger className="w-full" aria-labelledby={`f-${filter.key}`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('common.all')}</SelectItem>
              <SelectItem value="true">{filter.trueLabel ?? t('common.yes')}</SelectItem>
              <SelectItem value="false">{filter.falseLabel ?? t('common.no')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      );
    }
    case 'dateRange':
      return (
        <fieldset className="flex w-full gap-2 sm:w-auto">
          <legend className="sr-only">{filter.label}</legend>
          <label className="w-full space-y-1 sm:w-40">
            <span className="text-xs text-muted-foreground">
              {filter.label} · {t('common.from')}
            </span>
            <DateInput
              value={(values[`${filter.field}.gte`] as string) ?? null}
              onChange={(v) => setFilter(`${filter.field}.gte`, v)}
            />
          </label>
          <label className="w-full space-y-1 sm:w-40">
            <span className="text-xs text-muted-foreground">{t('common.to')}</span>
            <DateInput
              value={(values[`${filter.field}.lte`] as string) ?? null}
              onChange={(v) => setFilter(`${filter.field}.lte`, v)}
            />
          </label>
        </fieldset>
      );
    case 'date':
      return (
        <label className="w-full space-y-1 sm:w-40">
          <span className="text-xs text-muted-foreground">{filter.label}</span>
          <DateInput value={(values[filter.key] as string) ?? null} onChange={(v) => setFilter(filter.key, v)} />
        </label>
      );
    case 'text':
      return (
        <label className="w-full space-y-1 sm:w-40">
          <span className="text-xs text-muted-foreground">{filter.label}</span>
          <Input value={(values[filter.key] as string) ?? ''} onChange={(e) => setFilter(filter.key, e.target.value)} />
        </label>
      );
    case 'entity':
      return (
        <div className="w-full space-y-1 sm:w-56">
          <span className="text-xs text-muted-foreground" id={`f-${filter.key}`}>
            {filter.label}
          </span>
          <EntityPicker
            source={filter.source}
            value={(values[filter.key] as string) ?? null}
            onChange={(v) => setFilter(filter.key, v)}
            aria-labelledby={`f-${filter.key}`}
          />
        </div>
      );
  }
}
