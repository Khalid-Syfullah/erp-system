// Query and mutation helpers for company-scoped server state.
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryOptions,
} from '@tanstack/react-query';
import { useCallback, useRef } from 'react';
import type { CompanyApi } from './client';
import { isApiError } from './errors';
import { listQuery, type ListParams, type Page } from './list';
import { useCompany, useOptionalCompany } from '@/auth/company';

export function companyKey(companyId: string, ...parts: unknown[]): unknown[] {
  return ['c', companyId, ...parts];
}

/** A query against the active company's API; its key is prefixed with the company. */
export function useCompanyQuery<T>(
  key: unknown[],
  fn: (api: CompanyApi, signal: AbortSignal) => Promise<T>,
  options: Omit<UseQueryOptions<T, Error, T>, 'queryKey' | 'queryFn'> = {},
) {
  const { api, companyId } = useCompany();
  return useQuery<T, Error, T>({
    queryKey: companyKey(companyId, ...key),
    queryFn: ({ signal }) => fn(api, signal),
    ...options,
  });
}

/**
 * One page of a list endpoint; the previous page stays visible while the next one loads. Outside a
 * company (system administration) the API argument is null and the key is global.
 */
export function useCompanyList<T>(
  key: string,
  fetchPage: (api: CompanyApi, query: ReturnType<typeof listQuery>, signal: AbortSignal) => Promise<Page<T>>,
  params: ListParams,
  options: { enabled?: boolean; pollWhile?: (page: Page<T>) => boolean } = {},
) {
  const company = useOptionalCompany();
  return useQuery<Page<T>>({
    queryKey: company ? companyKey(company.companyId, key, 'list', params) : ['global', key, 'list', params],
    queryFn: ({ signal }) => fetchPage(company?.api as CompanyApi, listQuery(params), signal),
    placeholderData: keepPreviousData,
    enabled: options.enabled,
    refetchInterval: options.pollWhile ? (q) => (q.state.data && options.pollWhile!(q.state.data) ? 3000 : false) : undefined,
  });
}

/** A new Idempotency-Key per user intent, reused when the same submission is retried (API.md §10). */
export function useIdempotencyKey() {
  const key = useRef<string>(newIdempotencyKey());
  const renew = useCallback(() => {
    key.current = newIdempotencyKey();
  }, []);
  return { current: () => key.current, renew };
}

export function newIdempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `k-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

/** Whether an error is a definitive answer for an idempotency key (a retry would replay it). */
function isFinal(error: unknown): boolean {
  return (
    isApiError(error) &&
    error.status >= 400 &&
    error.status < 500 &&
    !['RESOURCE_BUSY', 'IDEMPOTENCY_IN_PROGRESS', 'NETWORK_ERROR'].includes(error.code)
  );
}

export interface CompanyMutationOptions<TVars, TResult> {
  mutationFn: (api: CompanyApi, vars: TVars, idempotencyKey: string) => Promise<TResult>;
  onSuccess?: (result: TResult, vars: TVars) => void | Promise<void>;
  onError?: (error: Error, vars: TVars) => void;
}

/**
 * A write against the active company. Successful writes invalidate the company's cached server state,
 * because one posting changes stock, ledgers and documents of other modules alike.
 */
export function useCompanyMutation<TVars = void, TResult = unknown>(options: CompanyMutationOptions<TVars, TResult>) {
  const { api, companyId } = useCompany();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  return useMutation<TResult, Error, TVars>({
    mutationFn: (vars) => options.mutationFn(api, vars, key.current()),
    onSuccess: async (result, vars) => {
      key.renew();
      await queryClient.invalidateQueries({ queryKey: companyKey(companyId) });
      await options.onSuccess?.(result, vars);
    },
    onError: (error, vars) => {
      if (isFinal(error)) key.renew();
      options.onError?.(error, vars);
    },
  });
}
