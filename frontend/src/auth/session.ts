// The signed-in user and their companies (GET /api/v1/me, API.md §17.1). Permissions shown here only
// shape the UI (SECURITY.md §4.4): the backend checks every request again.
import { queryOptions, useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/api/client';
import { configureFormatting } from '@/lib/format';
import { syncProfileLanguage } from './language';

export type Profile = Schemas['ProfileResponse'];
export type CompanyAccess = Schemas['CompanyAccessResponse'];
export type User = Schemas['UserResponse'];

export const meQuery = queryOptions({
  queryKey: ['me'] as const,
  queryFn: async () => {
    const me = await api.get('/api/v1/me', {}, { anonymous: true });
    // The user's saved language applies on every device (it may reload the page into it).
    await syncProfileLanguage(me.user);
    configureFormatting({ locale: me.user?.locale, timeZone: me.user?.timezone });
    return me;
  },
  staleTime: 60_000,
  retry: false,
});

export function useMe(): Profile {
  const { data } = useQuery(meQuery);
  if (!data) throw new Error('useMe() outside the signed-in area');
  return data;
}

export function companiesOf(profile: Profile | undefined): CompanyAccess[] {
  return [...(profile?.companies ?? [])].sort((a, b) => (a.displayName ?? '').localeCompare(b.displayName ?? ''));
}
