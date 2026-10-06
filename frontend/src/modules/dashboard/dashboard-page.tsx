import { Link } from '@tanstack/react-router';
import { ArrowRight } from 'lucide-react';
import { useState } from 'react';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { useMe } from '@/auth/session';
import { PageHeader } from '@/components/common/page';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { t } from '@/i18n';
import { formatDate, formatDecimal, formatMoney } from '@/lib/format';

type Widget = Schemas['Widget'];

/** KPI cards of the role-specific dashboards (API.md §17.11); widgets the user may not see are omitted by the server. */
export function DashboardPage({ title }: { title?: string } = {}) {
  const me = useMe();
  const { companyId, company } = useCompany();
  const list = useCompanyQuery(['dashboards'], (api, signal) => api.get('/dashboards', null, { signal }));
  const [code, setCode] = useState<string | null>(null);
  const dashboards = (list.data ?? []).filter((d) => (d.visibleWidgets ?? 0) > 0);
  const selected = code ?? dashboards[0]?.code ?? null;
  const dashboard = useCompanyQuery(
    ['dashboards', selected],
    (api, signal) => api.get('/dashboards/{dashboardCode}', { dashboardCode: selected! }, { signal }),
    { enabled: !!selected },
  );

  return (
    <div className="space-y-4">
      <PageHeader
        title={title ?? t('dashboard.welcome', { name: me.user?.displayName ?? '' })}
        description={dashboard.data?.asOf ? `${company.displayName} · ${t('dashboard.asOf', { date: formatDate(dashboard.data.asOf) })}` : company.displayName}
        actions={
          dashboards.length > 1 ? (
            <Select value={selected ?? undefined} onValueChange={setCode}>
              <SelectTrigger className="w-48" aria-label={t('dashboard.choose')}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {dashboards.map((d) => (
                  <SelectItem key={d.code} value={d.code!}>
                    {d.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : null
        }
      />
      {list.isLoading || dashboard.isLoading ? (
        <LoadingState />
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => list.refetch()} />
      ) : dashboard.isError ? (
        <ErrorState error={dashboard.error} onRetry={() => dashboard.refetch()} />
      ) : !dashboard.data || (dashboard.data.widgets ?? []).length === 0 ? (
        <EmptyState title={t('dashboard.noWidgets')} />
      ) : (
        <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label={dashboard.data.name}>
          {(dashboard.data.widgets ?? []).map((widget) => (
            <WidgetCard key={widget.code} widget={widget} companyId={companyId} />
          ))}
        </ul>
      )}
    </div>
  );
}

function WidgetCard({ widget, companyId }: { widget: Widget; companyId: string }) {
  const hasAmount = widget.amount !== null && widget.amount !== undefined;
  return (
    <li className="flex flex-col justify-between gap-3 rounded-lg border bg-card p-4">
      <div className="space-y-1">
        <h2 className="text-sm text-muted-foreground">{widget.label}</h2>
        <p className="text-2xl font-semibold tabular">
          {hasAmount ? formatMoney(widget.amount, widget.currencyCode) : formatDecimal(String(widget.count ?? 0))}
        </p>
        {hasAmount && widget.count !== null && widget.count !== undefined ? (
          <p className="text-xs text-muted-foreground">{t('dashboard.count', { count: widget.count })}</p>
        ) : null}
        {widget.secondaryCount ? (
          <p className="text-xs text-status-warning-fg">{t('dashboard.secondary', { count: widget.secondaryCount })}</p>
        ) : null}
      </div>
      {widget.reportCode ? (
        <Link
          to="/c/$companyId/reports/$reportCode"
          params={{ companyId, reportCode: widget.reportCode }}
          className="inline-flex items-center gap-1 text-sm text-primary underline-offset-4 hover:underline"
        >
          {t('dashboard.openReport')}
          <span className="sr-only">: {widget.label}</span>
          <ArrowRight className="size-3.5" aria-hidden />
        </Link>
      ) : null}
    </li>
  );
}
