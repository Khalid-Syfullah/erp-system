import { QueryClient } from '@tanstack/react-query';
import { isApiError } from '@/api/errors';

/** Server state lives in TanStack Query; the backend is the source of truth for everything shown. */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        refetchOnWindowFocus: false,
        // Client errors (4xx) are answers, not glitches: retrying them only repeats the problem.
        retry: (failureCount, error) =>
          failureCount < 2 && !(isApiError(error) && error.status >= 400 && error.status < 500),
      },
      mutations: { retry: false },
    },
  });
}
