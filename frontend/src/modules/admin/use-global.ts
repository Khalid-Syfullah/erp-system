import { useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';

/** A query of system administration data, cached under the global key. */
export function useGlobalQuery<T>(key: unknown[], fn: (signal: AbortSignal) => Promise<T>, enabled = true) {
  return useQuery<T>({ queryKey: ['global', ...key] as QueryKey, queryFn: ({ signal }) => fn(signal), enabled });
}

export function useInvalidateGlobal() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ['global'] });
}
