import { Plus } from 'lucide-react';
import { useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText } from '@/components/common/values';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { DateField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { t } from '@/i18n';

type FiscalYear = Schemas['FiscalYear'];
type Period = Schemas['AccountingPeriod'];

const periodActions: DocAction<Period>[] = [
  {
    id: 'soft-close',
    label: t('acc.softClose'),
    when: inStatus('OPEN'),
    permissions: ['accounting.period.soft_close'],
    confirm: { title: t('acc.softCloseConfirm') },
    run: (c, p, ctx) => c.post('/periods/{periodId}/soft-close', { periodId: p.id! }, { ifMatch: p.version, idempotencyKey: ctx.idempotencyKey }),
    success: t('enums.SOFT_CLOSED'),
  },
  {
    id: 'close',
    label: t('acc.closePeriod'),
    when: inStatus('OPEN', 'SOFT_CLOSED'),
    permissions: ['accounting.period.close'],
    confirm: { title: t('acc.closePeriodConfirm') },
    run: (c, p, ctx) => c.post('/periods/{periodId}/close', { periodId: p.id! }, { ifMatch: p.version, idempotencyKey: ctx.idempotencyKey }),
    success: t('enums.CLOSED'),
  },
  {
    id: 'reopen',
    label: t('acc.reopen'),
    when: inStatus('SOFT_CLOSED', 'CLOSED'),
    permissions: ['accounting.period.reopen'],
    confirm: { title: t('acc.reopen'), reason: 'required', reasonLabel: t('acc.reopenReason') },
    run: (c, p, ctx) => c.post('/periods/{periodId}/reopen', { periodId: p.id! }, { body: { reason: ctx.reason }, ifMatch: p.version, idempotencyKey: ctx.idempotencyKey }),
    success: t('enums.OPEN'),
  },
];

/** Period close (PRODUCT_SPEC.md §8.5): soft-close, close and reopen per period; year close. */
export function PeriodsPage() {
  const { api, can } = useCompany();
  const years = useCompanyQuery(['fiscal-years'], (c, signal) => c.get('/fiscal-years', null, { query: { limit: 50 }, signal }));
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('acc.periodsTitle')}
        actions={can('accounting.fiscal_year.manage') ? <Button onClick={() => setCreating(true)}><Plus aria-hidden />{t('acc.newFiscalYear')}</Button> : null}
      />
      {years.isLoading ? <LoadingState /> : years.isError ? <ErrorState error={years.error} onRetry={() => years.refetch()} /> : (years.data?.data ?? []).length === 0 ? <EmptyState /> : (
        (years.data?.data ?? []).map((y) => <FiscalYearSection key={y.id} yearId={y.id!} />)
      )}
      <FormDialog
        open={creating}
        onOpenChange={setCreating}
        title={t('acc.newFiscalYear')}
        schema={z.object({ startDate: zf.date() })}
        defaults={{ startDate: `${new Date().getFullYear() + 1}-01-01` }}
        success={t('common.created')}
        onSubmit={(v) => api.post('/fiscal-years', null, { body: { startDate: v.startDate! } })}
      >
        <DateField name="startDate" label={t('acc.startDate')} required />
      </FormDialog>
    </div>
  );
}

function FiscalYearSection({ yearId }: { yearId: string }) {
  const year = useCompanyQuery(['fiscal-years', yearId], (c, signal) => c.get('/fiscal-years/{yearId}', { yearId }, { signal }));
  if (year.isLoading) return <LoadingState />;
  if (year.isError || !year.data) return <ErrorState error={year.error} />;
  const y = year.data;
  const actions: DocAction<FiscalYear>[] = [
    {
      id: 'close-year',
      label: t('acc.closeYear'),
      when: inStatus('OPEN'),
      permissions: ['accounting.fiscal_year.close'],
      confirm: { title: t('acc.closeYearConfirm') },
      run: (c, x, ctx) => c.post('/fiscal-years/{yearId}/close', { yearId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.CLOSED'),
    },
  ];
  return (
    <Section
      title={
        <span className="flex items-center gap-2">
          {t('acc.fiscalYear')} {y.code} · <DateText value={y.startDate} /> – <DateText value={y.endDate} />
          <StatusBadge status={y.status} />
        </span>
      }
      actions={<DocumentActions doc={y} actions={actions} />}
      bodyClassName="p-0"
    >
      <div className="overflow-x-auto">
        <Table>
          <caption className="sr-only">{`${t('acc.fiscalYear')} ${y.code}`}</caption>
          <TableHeader>
            <TableRow>
              <TableHead>{t('acc.period')}</TableHead>
              <TableHead>{t('acc.startDate')}</TableHead>
              <TableHead>{t('acc.endDate')}</TableHead>
              <TableHead>{t('common.status')}</TableHead>
              <TableHead className="hidden md:table-cell">{t('acc.closedAt')}</TableHead>
              <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {((y.periods ?? []) as unknown as Period[]).map((p) => (
              <TableRow key={p.id}>
                <TableCell className="tabular">{p.periodNo}</TableCell>
                <TableCell><DateText value={p.startDate} /></TableCell>
                <TableCell><DateText value={p.endDate} /></TableCell>
                <TableCell><StatusBadge status={p.status} /></TableCell>
                <TableCell className="hidden md:table-cell"><DateTimeText value={p.closedAt} /></TableCell>
                <TableCell className="text-right"><DocumentActions doc={p} actions={periodActions} /></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </Section>
  );
}
