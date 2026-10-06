import { createFileRoute, notFound, Outlet } from '@tanstack/react-router';
import { useEffect } from 'react';
import { CompanyProvider } from '@/auth/company';
import { rememberCompany } from '@/auth/last-company';
import { meQuery, useMe } from '@/auth/session';

/** A company's area. Companies the user has no assignment in are not found (API.md §3). */
export const Route = createFileRoute('/_authed/c/$companyId')({
  beforeLoad: async ({ context, params }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    if (!me.companies?.some((c) => c.id === params.companyId)) throw notFound();
  },
  component: CompanyLayout,
});

function CompanyLayout() {
  const { companyId } = Route.useParams();
  const me = useMe();
  const company = me.companies?.find((c) => c.id === companyId);
  useEffect(() => rememberCompany(companyId), [companyId]);
  if (!company) return null;
  return (
    <CompanyProvider company={company}>
      <Outlet />
    </CompanyProvider>
  );
}
