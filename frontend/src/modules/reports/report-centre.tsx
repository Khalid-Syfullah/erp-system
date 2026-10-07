import { Link, useNavigate } from '@tanstack/react-router';
import { Download, FileBarChart2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { Schemas } from '@/api/client';
import { download } from '@/api/client';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateTimeText, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { DocumentActions, type DocAction } from '@/components/document/actions';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { enumLabel, searchable, serverText, t, tryT } from '@/i18n';
import { enums } from '@/lib/enums';
import { DashboardPage } from '@/modules/dashboard/dashboard-page';
import { useReportCatalogue } from './report-run-page';

const MODULE_ORDER = ['sales', 'procurement', 'inventory', 'accounting', 'hr', 'payroll'];

/** The reports this user may run, by module, with a quick filter. */
export function ReportCentrePage() {
  const { companyId } = useCompany();
  const catalogue = useReportCatalogue();
  const [filter, setFilter] = useState('');
  const groups = useMemo(() => {
    const term = searchable(filter.trim());
    const map = new Map<string, Schemas['Report'][]>();
    for (const r of catalogue.data ?? []) {
      // Both languages match: the shown (Bangla) names and the English ones people may know.
      const text = searchable(`${serverText(r.name)} ${serverText(r.description)} ${r.name} ${r.description} ${r.code}`);
      if (term && !text.includes(term)) continue;
      const list = map.get(r.module ?? '') ?? [];
      list.push(r);
      map.set(r.module ?? '', list);
    }
    return [...map.entries()].sort(([a], [b]) => MODULE_ORDER.indexOf(a) - MODULE_ORDER.indexOf(b));
  }, [catalogue.data, filter]);
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('rep.centreTitle')}
        description={t('rep.centreText')}
        actions={<Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t('common.searchPlaceholder')} aria-label={t('common.search')} className="w-64" />}
      />
      {catalogue.isLoading ? <LoadingState /> : catalogue.isError ? <ErrorState error={catalogue.error} onRetry={() => catalogue.refetch()} /> : groups.length === 0 ? <EmptyState filtered={!!filter} /> : (
        groups.map(([module, reports]) => (
          <Section key={module} title={tryT(`rep.modules.${module}`) ?? enumLabel(module)}>
            <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {reports.map((r) => (
                <li key={r.code}>
                  <Link
                    to="/c/$companyId/reports/$reportCode"
                    params={{ companyId, reportCode: r.code! }}
                    className="flex h-full gap-3 rounded-lg border p-3 hover:border-primary hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                  >
                    <FileBarChart2 className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
                    <span>
                      <span className="block font-medium">{serverText(r.name)}</span>
                      <span className="block text-xs text-muted-foreground">{serverText(r.description)}</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          </Section>
        ))
      )}
    </div>
  );
}

type Job = Schemas['ExportJob'];

/** The user's export jobs (QUEUED → RUNNING → SUCCEEDED/FAILED, later EXPIRED), polled while pending. */
export function ExportsPage() {
  const { api } = useCompany();
  return (
    <div className="space-y-4">
      <PageHeader title={t('rep.exportsTitle')} />
      <DataTable<Job>
        id="report-exports"
        fetchPage={(c, query, signal) => c.get('/report-exports', null, { query, signal })}
        pollWhile={(jobs) => jobs.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING')}
        rowKey={(j) => j.id!}
        searchable={false}
        defaultSort="-requestedAt"
        filters={[{ kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.exportStatus] }]}
        columns={[
          { id: 'report', header: t('rep.report'), cell: (j) => j.reportCode },
          { id: 'format', header: t('rep.format'), cell: (j) => enumLabel(j.format) },
          { id: 'requested', header: t('rep.requested'), sortKey: 'requestedAt', cell: (j) => <DateTimeText value={j.requestedAt} /> },
          { id: 'rows', header: t('rep.rows'), align: 'right', hideBelow: 'sm', cell: (j) => <Text value={j.rowCount} /> },
          { id: 'expires', header: t('rep.expires'), hideBelow: 'md', cell: (j) => <DateTimeText value={j.expiresAt} /> },
          { id: 'status', header: t('common.status'), cell: (j) => <>{<StatusBadge status={j.status} />} {j.errorCode ? <span className="text-xs text-muted-foreground">{enumLabel(j.errorCode)}</span> : null}</> },
          {
            id: 'download',
            header: <span className="sr-only">{t('common.download')}</span>,
            align: 'right',
            cell: (j) =>
              j.status === 'SUCCEEDED' ? (
                <Button size="sm" variant="ghost" onClick={() => void download(api.url('/report-exports/{exportId}/content', { exportId: j.id! }), `${j.reportCode}.${(j.format ?? 'csv').toLowerCase()}`).catch(notify.error)}>
                  <Download aria-hidden />
                  {t('common.download')}
                </Button>
              ) : null,
          },
        ]}
      />
    </div>
  );
}

type Saved = Schemas['SavedReport'];

/** Own and shared saved reports; opening one runs the report with its parameters. */
export function SavedReportsPage() {
  const { companyId } = useCompany();
  const navigate = useNavigate();
  const open = (s: Saved) =>
    void navigate({ to: '/c/$companyId/reports/$reportCode', params: { companyId, reportCode: s.reportCode! }, search: { ...(s.parameters as Record<string, string>), run: '1' } as never });
  const actions: DocAction<Saved>[] = [
    { id: 'open', label: t('rep.open'), primary: true, open },
    {
      id: 'share',
      label: t('rep.shared'),
      when: (s) => !!s.owned && !s.isShared,
      permissions: ['reporting.saved_report.share'],
      run: (c, s) => c.patch('/saved-reports/{savedReportId}', { savedReportId: s.id! }, { body: { isShared: true }, ifMatch: s.version }),
    },
    {
      id: 'delete',
      label: t('rep.deleteSaved'),
      variant: 'destructive',
      when: (s) => !!s.owned,
      confirm: { title: t('rep.deleteSaved'), destructive: true },
      run: (c, s) => c.delete('/saved-reports/{savedReportId}', { savedReportId: s.id! }, { ifMatch: s.version }),
    },
  ];
  return (
    <div className="space-y-4">
      <PageHeader title={t('rep.savedReportsTitle')} />
      <DataTable<Saved>
        id="saved-reports"
        fetchPage={(c, query, signal) => c.get('/saved-reports', null, { query, signal })}
        rowKey={(s) => s.id!}
        defaultSort="name"
        filters={[{ kind: 'boolean', key: 'isShared', label: t('rep.shared') }]}
        onRowOpen={open}
        columns={[
          {
            id: 'name',
            header: t('rep.savedName'),
            sortKey: 'name',
            cell: (s) => (
              <Button variant="link" className="h-auto p-0" data-row-link onClick={() => open(s)}>
                {s.name}
              </Button>
            ),
          },
          { id: 'report', header: t('rep.report'), cell: (s) => s.reportCode },
          { id: 'owner', header: t('rep.owner'), cell: (s) => (s.owned ? t('rep.mine') : t('rep.sharedBy')) },
          { id: 'shared', header: t('rep.shared'), hideBelow: 'sm', cell: (s) => (s.isShared ? t('common.yes') : t('common.no')) },
          { id: 'actions', header: <span className="sr-only">{t('common.actions')}</span>, align: 'right', cell: (s) => <DocumentActions doc={s} actions={actions.filter((a) => a.id !== 'open')} /> },
        ]}
      />
    </div>
  );
}

export function DashboardsPage() {
  return <DashboardPage title={t('rep.dashboardsTitle')} />;
}
