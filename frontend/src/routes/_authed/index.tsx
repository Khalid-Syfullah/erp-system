import { createFileRoute, redirect } from '@tanstack/react-router';
import { lastCompanyId } from '@/auth/last-company';
import { companiesOf, meQuery } from '@/auth/session';
import { EmptyState } from '@/components/feedback/states';
import { t } from '@/i18n';

/** Home: the last used company's dashboard, the first company, or a hint when there is none. */
export const Route = createFileRoute('/_authed/')({
  beforeLoad: async ({ context }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    const companies = companiesOf(me);
    const last = lastCompanyId();
    const target = companies.find((c) => c.id === last) ?? companies[0];
    if (target?.id) throw redirect({ to: '/c/$companyId', params: { companyId: target.id } });
    if (me.user?.isSystemAdmin) throw redirect({ to: '/admin/users' });
  },
  component: () => <EmptyState className="min-h-[50vh]" title={t('shell.noCompanies')} />,
});
