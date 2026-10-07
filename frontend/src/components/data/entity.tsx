import { useQuery } from '@tanstack/react-query';
import { Check, ChevronsUpDown, X } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import type { CompanyApi } from '@/api/client';
import { companyKey } from '@/api/hooks';
import { listQuery, type Filters, type Page } from '@/api/list';
import { useCompany } from '@/auth/company';
import { Button } from '@/components/ui/button';
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/ui/command';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { searchable, t } from '@/i18n';
import { useDebounced } from '@/lib/use-debounced';
import { cn } from '@/lib/utils';
import type { EntitySource } from './entities';

const ALL_PAGE = 200;
const MAX_PAGES = 25;

/** Loads every page of a list (small master data), following the cursors. */
async function loadAll<T>(source: EntitySource<T>, api: CompanyApi, signal?: AbortSignal): Promise<T[]> {
  const items: T[] = [];
  let cursor: string | null | undefined = null;
  for (let i = 0; i < MAX_PAGES; i++) {
    const page: Page<T> = await source.list(
      api,
      listQuery({ limit: ALL_PAGE, cursor, filters: source.defaultFilters }),
      signal,
    );
    items.push(...(page.data ?? []));
    if (!page.page?.hasMore || !page.page.nextCursor) break;
    cursor = page.page.nextCursor;
  }
  return items;
}

function sourceKey(companyId: string, source: EntitySource<unknown>, ...parts: unknown[]) {
  return source.global ? ['ref', source.key, ...parts] : companyKey(companyId, 'entity', source.key, ...parts);
}

/** All records of an 'all' source, indexed by ID. */
export function useEntityIndex<T>(source: EntitySource<T>, enabled = true) {
  const { api, companyId, can } = useCompany();
  const allowed = !source.permission || can(source.permission);
  const query = useQuery({
    queryKey: sourceKey(companyId, source as EntitySource<unknown>, 'all'),
    queryFn: ({ signal }) => loadAll(source, api, signal),
    enabled: enabled && allowed && source.mode === 'all',
    staleTime: 5 * 60_000,
  });
  const index = useMemo(() => {
    const map = new Map<string, T>();
    for (const item of query.data ?? []) map.set(source.id(item), item);
    return map;
  }, [query.data, source]);
  return { ...query, items: query.data ?? [], index, allowed };
}

const BATCH_SIZE = 100;
const BATCH_WINDOW_MS = 10;

interface Waiter {
  resolve: (item: unknown) => void;
  reject: (error: unknown) => void;
}

const batchQueues = new Map<string, Map<string, Waiter[]>>();

/**
 * Fetches a record of a 'search' source by ID through its `batch` lookup: the IDs requested within a
 * few milliseconds (the rows of a table) go out together, at most 100 per request, instead of one
 * request per cell. Resolves to null for IDs the server does not return (unknown or out of scope).
 */
export function loadBatched<T>(source: EntitySource<T>, api: CompanyApi, companyId: string, id: string): Promise<T | null> {
  const key = `${companyId}|${source.key}`;
  return new Promise((resolve, reject) => {
    let queue = batchQueues.get(key);
    if (!queue) {
      const pending = new Map<string, Waiter[]>();
      batchQueues.set(key, pending);
      queue = pending;
      setTimeout(() => {
        batchQueues.delete(key);
        const ids = [...pending.keys()];
        for (let i = 0; i < ids.length; i += BATCH_SIZE) {
          const chunk = ids.slice(i, i + BATCH_SIZE);
          source.batch!(api, chunk).then(
            (items) => {
              const found = new Map(items.map((item) => [source.id(item), item]));
              for (const each of chunk) pending.get(each)!.forEach((w) => w.resolve(found.get(each) ?? null));
            },
            (error: unknown) => {
              for (const each of chunk) pending.get(each)!.forEach((w) => w.reject(error));
            },
          );
        }
      }, BATCH_WINDOW_MS);
    }
    const waiters = queue.get(id) ?? [];
    waiters.push({ resolve: resolve as (item: unknown) => void, reject });
    queue.set(id, waiters);
  });
}

/** One record by ID: from the loaded index for 'all' sources, batched or by GET for 'search' sources. */
export function useEntity<T>(source: EntitySource<T>, id: string | null | undefined) {
  const { api, companyId, can } = useCompany();
  const allowed = !source.permission || can(source.permission);
  const all = useEntityIndex(source, source.mode === 'all' && !!id);
  const single = useQuery({
    queryKey: sourceKey(companyId, source as EntitySource<unknown>, 'id', id),
    queryFn: ({ signal }) => (source.batch ? loadBatched(source, api, companyId, id!) : source.get!(api, id!, signal)),
    enabled: source.mode === 'search' && !!id && allowed && (!!source.batch || !!source.get),
    staleTime: 5 * 60_000,
    retry: false,
  });
  if (source.mode === 'all') {
    return { item: id ? all.index.get(id) : undefined, isLoading: all.isLoading, allowed };
  }
  return { item: single.data ?? undefined, isLoading: single.isLoading, allowed };
}

function shortId(id: string): string {
  return `…${id.slice(-6)}`;
}

/** The label of a referenced record (customer, warehouse, account …). */
export function EntityName<T>({
  source,
  id,
  fallback,
  className,
}: {
  source: EntitySource<T>;
  id: string | null | undefined;
  fallback?: ReactNode;
  className?: string;
}) {
  const { item, isLoading } = useEntity(source, id);
  if (!id) return <span className="text-muted-foreground">{fallback ?? '—'}</span>;
  if (item) return <span className={className}>{source.label(item)}</span>;
  if (isLoading) return <span className="text-muted-foreground">…</span>;
  return (
    <span className={cn('font-mono text-xs text-muted-foreground', className)} title={id}>
      {shortId(id)}
    </span>
  );
}

export interface EntityPickerProps<T> {
  source: EntitySource<T>;
  value: string | null | undefined;
  onChange: (id: string | null, item?: T) => void;
  id?: string;
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  clearable?: boolean;
  filters?: Filters;
  /** Extra condition for the offered records (e.g. only stockable variants). */
  filter?: (item: T) => boolean;
  className?: string;
  'aria-describedby'?: string;
  'aria-labelledby'?: string;
  'aria-label'?: string;
  size?: 'default' | 'lg';
}

/** An accessible combobox for choosing a referenced record, searching the server for large sources. */
export function EntityPicker<T>({
  source,
  value,
  onChange,
  id,
  placeholder,
  disabled,
  invalid,
  clearable = true,
  filters,
  filter,
  className,
  size = 'default',
  ...aria
}: EntityPickerProps<T>) {
  const { api, companyId } = useCompany();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const debounced = useDebounced(search, 250);
  const selected = useEntity(source, value);
  const all = useEntityIndex(source, source.mode === 'all');
  const remote = useQuery({
    queryKey: sourceKey(companyId, source as EntitySource<unknown>, 'search', debounced.trim(), filters),
    queryFn: ({ signal }) =>
      source.list(api, listQuery({ limit: 20, q: debounced.trim(), filters: { ...source.defaultFilters, ...filters } }), signal),
    enabled: open && source.mode === 'search' && selected.allowed,
    staleTime: 30_000,
  });

  const options = useMemo(() => {
    const base = source.mode === 'all' ? all.items : (remote.data?.data ?? []);
    const term = source.mode === 'all' ? searchable(search.trim()) : '';
    return base
      .filter((item) => (source.selectable ? source.selectable(item) : true))
      .filter((item) => (filter ? filter(item) : true))
      .filter((item) => {
        if (!term) return true;
        return searchable(`${source.label(item)} ${source.description?.(item) ?? ''}`).includes(term);
      })
      .slice(0, 100);
  }, [source, all.items, remote.data, search, filter]);

  const label = selected.item ? source.label(selected.item) : value ? shortId(value) : '';
  const loading = source.mode === 'all' ? all.isLoading : remote.isFetching;

  return (
    <div className={cn('flex min-w-0 items-center gap-1', className)}>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id={id}
            type="button"
            variant="outline"
            role="combobox"
            aria-expanded={open}
            aria-invalid={invalid || undefined}
            disabled={disabled}
            className={cn(
              'w-full min-w-0 justify-between font-normal',
              size === 'lg' && 'h-11 text-base',
              !label && 'text-muted-foreground',
            )}
            {...aria}
          >
            <span className="truncate">{label || placeholder || t('common.select')}</span>
            <ChevronsUpDown className="opacity-50" aria-hidden />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[--radix-popover-trigger-width] min-w-72 p-0" align="start">
          <Command shouldFilter={false}>
            <CommandInput value={search} onValueChange={setSearch} placeholder={t('common.searchPlaceholder')} />
            <CommandList>
              {loading ? (
                <div className="p-3 text-sm text-muted-foreground">{t('app.loading')}</div>
              ) : (
                <CommandEmpty>
                  {source.mode === 'search' && search.trim().length === 1 ? t('common.typeToSearch') : t('common.noOptions')}
                </CommandEmpty>
              )}
              <CommandGroup>
                {options.map((item) => {
                  const itemId = source.id(item);
                  return (
                    <CommandItem
                      key={itemId}
                      value={itemId}
                      onSelect={() => {
                        onChange(itemId, item);
                        setOpen(false);
                        setSearch('');
                      }}
                    >
                      <Check className={cn('size-4', itemId === value ? 'opacity-100' : 'opacity-0')} aria-hidden />
                      <div className="min-w-0">
                        <div className="truncate">{source.label(item)}</div>
                        {source.description?.(item) ? (
                          <div className="truncate text-xs text-muted-foreground">{source.description(item)}</div>
                        ) : null}
                      </div>
                    </CommandItem>
                  );
                })}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
      {clearable && value && !disabled ? (
        <Button type="button" variant="ghost" size="icon-sm" onClick={() => onChange(null)} aria-label={t('common.clear')}>
          <X aria-hidden />
        </Button>
      ) : null}
    </div>
  );
}
