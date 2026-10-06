import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Download, Plus } from 'lucide-react';
import { useState } from 'react';
import { z } from 'zod';
import { download, type Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { Code, DateText, DateTimeText, Money, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, LinesTable } from '@/components/document/document-layout';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, DecimalField, EntityField, SelectField, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';

type Run = Schemas['Run'];
type Period = Schemas['PayrollPeriod'];

// --- Periods and inputs -----------------------------------------------------------------------

export function PayrollPeriodsPage() {
  const { companyId } = useCompany();
  return (
    <div className="space-y-4">
      <PageHeader title={t('pay.periodsTitle')} />
      <DataTable<Period>
        id="payroll-periods"
        fetchPage={(c, query, signal) => c.get('/payroll-periods', null, { query, signal })}
        rowKey={(p) => p.id!}
        defaultSort="startDate"
        searchable={false}
        filters={[
          { kind: 'entity', key: 'payScheduleId', label: t('pay.schedule'), source: entities.paySchedule },
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.payrollPeriodStatus] },
        ]}
        columns={[
          {
            id: 'period',
            header: t('pay.period'),
            sortKey: 'startDate',
            cell: (p) => (
              <Link to="/c/$companyId/payroll/periods/$periodId" params={{ companyId, periodId: p.id! }} data-row-link className="font-medium text-primary hover:underline">
                <DateText value={p.startDate} /> – <DateText value={p.endDate} />
              </Link>
            ),
          },
          { id: 'schedule', header: t('pay.schedule'), hideBelow: 'sm', cell: (p) => <EntityName source={entities.paySchedule} id={p.payScheduleId} /> },
          { id: 'payDate', header: t('pay.payDate'), cell: (p) => <DateText value={p.payDate} /> },
          { id: 'status', header: t('common.status'), cell: (p) => <StatusBadge status={p.status} /> },
        ]}
      />
    </div>
  );
}

const periodRoute = getRouteApi('/_authed/c/$companyId/payroll/periods/$periodId');
const inputSchema = z.object({ employeeId: zf.id(), componentId: zf.id(), quantity: zf.optionalDecimal(), amount: zf.optionalDecimal(), note: zf.optionalText(500) });

/** A payroll period with its variable inputs (overtime hours, bonuses …) for the runs. */
export function PayrollPeriodPage() {
  const { periodId } = periodRoute.useParams();
  const { api, companyId, can } = useCompany();
  const period = useCompanyQuery(['payroll-periods', periodId], (c, signal) => c.get('/payroll-periods/{periodId}', { periodId }, { signal }));
  const inputs = useCompanyQuery(['payroll-periods', periodId, 'inputs'], (c, signal) => c.get('/payroll-periods/{periodId}/inputs', { periodId }, { signal }), { enabled: can('payroll.run.prepare') });
  const [editing, setEditing] = useState<{ input?: Schemas['Input'] } | null>(null);
  const [removing, setRemoving] = useState<Schemas['Input'] | null>(null);
  if (period.isLoading) return <LoadingState />;
  if (period.isError || !period.data) return <ErrorState error={period.error} onRetry={() => period.refetch()} />;
  const p = period.data;
  const prepare = can('payroll.run.prepare');
  return (
    <div className="space-y-4">
      <PageHeader
        breadcrumbs={<BackLink to={`/c/${companyId}/payroll/periods`} label={t('pay.periodsTitle')} />}
        title={<><DateText value={p.startDate} /> – <DateText value={p.endDate} /></>}
        badge={<StatusBadge status={p.status} />}
        description={<EntityName source={entities.paySchedule} id={p.payScheduleId} />}
      />
      <Section>
        <DetailList items={[{ label: t('pay.payDate'), value: <DateText value={p.payDate} /> }]} />
      </Section>
      {prepare ? (
        <Section title={t('pay.inputs')} actions={<Button size="sm" onClick={() => setEditing({})}><Plus aria-hidden />{t('pay.newInput')}</Button>} bodyClassName="p-0">
          {inputs.isLoading ? <LoadingState /> : (inputs.data?.data ?? []).length === 0 ? <EmptyState className="py-6" /> : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('nav.employees')}</TableHead>
                    <TableHead>{t('pay.components')}</TableHead>
                    <TableHead className="text-right">{t('pay.quantity')}</TableHead>
                    <TableHead className="text-right">{t('pay.amount')}</TableHead>
                    <TableHead className="hidden md:table-cell">{t('common.notes')}</TableHead>
                    <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(inputs.data?.data ?? []).map((i) => (
                    <TableRow key={i.id}>
                      <TableCell><EntityName source={entities.employee} id={i.employeeId} /></TableCell>
                      <TableCell><EntityName source={entities.payComponent} id={i.componentId} /></TableCell>
                      <TableCell className="text-right"><Quantity value={i.quantity} /></TableCell>
                      <TableCell className="text-right"><Money value={i.amount} showCurrency={false} /></TableCell>
                      <TableCell className="hidden md:table-cell"><Text value={i.note} /></TableCell>
                      <TableCell className="text-right">
                        <Button size="sm" variant="ghost" onClick={() => setEditing({ input: i })}>{t('common.edit')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => setRemoving(i)}>{t('common.remove')}</Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </Section>
      ) : null}
      {editing ? (
        <FormDialog
          open
          onOpenChange={(open) => !open && setEditing(null)}
          title={editing.input ? t('pay.editInput') : t('pay.newInput')}
          schema={inputSchema}
          defaults={{ employeeId: editing.input?.employeeId ?? null, componentId: editing.input?.componentId ?? null, quantity: editing.input?.quantity ?? null, amount: editing.input?.amount ?? null, note: editing.input?.note ?? '' }}
          success={t('common.saved')}
          onSubmit={(v) => {
            const body = { employeeId: v.employeeId!, componentId: v.componentId!, quantity: v.quantity || undefined, amount: v.amount || undefined, note: v.note || undefined };
            return editing.input
              ? api.put('/payroll-periods/{periodId}/inputs/{inputId}', { periodId, inputId: editing.input.id! }, { body, ifMatch: editing.input.version })
              : api.post('/payroll-periods/{periodId}/inputs', { periodId }, { body });
          }}
        >
          <EntityField name="employeeId" label={t('nav.employees')} source={entities.employee} required />
          <EntityField name="componentId" label={t('pay.components')} source={entities.payComponent} filter={(c) => c.calculation === 'INPUT' || c.calculation === 'FIXED'} required />
          <DecimalField name="quantity" label={t('pay.quantity')} />
          <DecimalField name="amount" label={t('pay.amount')} />
          <TextField name="note" label={t('common.notes')} />
        </FormDialog>
      ) : null}
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('common.remove')}
        destructive
        confirmLabel={t('common.remove')}
        onConfirm={async () => {
          await api.delete('/payroll-periods/{periodId}/inputs/{inputId}', { periodId, inputId: removing!.id! });
          await inputs.refetch();
        }}
      />
    </div>
  );
}

// --- Runs -------------------------------------------------------------------------------------

const runSchema = z.object({ payrollPeriodId: zf.id(), runType: zf.id(), description: zf.optionalText(200), accountingDate: zf.optionalDate() });

export function PayrollRunsPage() {
  const { api, can, companyId } = useCompany();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('pay.runsTitle')} />
      <DataTable<Run>
        id="payroll-runs"
        fetchPage={(c, query, signal) => c.get('/payroll-runs', null, { query, signal })}
        rowKey={(r) => r.id!}
        defaultSort="-createdAt"
        searchable={false}
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.payrollRunStatus] },
          { kind: 'enum', key: 'runType', label: t('pay.runType'), values: [...enums.runType] },
        ]}
        columns={[
          {
            id: 'number',
            header: t('common.number'),
            cell: (r) => (
              <Link to="/c/$companyId/payroll/runs/$runId" params={{ companyId, runId: r.id! }} data-row-link className="font-medium text-primary hover:underline">
                {r.number ?? t('doc.draft')}
              </Link>
            ),
          },
          { id: 'period', header: t('pay.period'), cell: (r) => <EntityName source={entities.payrollPeriod} id={r.periodId} /> },
          { id: 'type', header: t('pay.runType'), hideBelow: 'sm', cell: (r) => enumLabel(r.runType) },
          { id: 'employees', header: t('pay.employees'), align: 'right', hideBelow: 'md', cell: (r) => <Text value={r.employeeCount} /> },
          { id: 'net', header: t('pay.net'), align: 'right', cell: (r) => <Money value={r.netTotal} currency={r.currencyCode} /> },
          { id: 'status', header: t('common.status'), cell: (r) => <StatusBadge status={r.status} /> },
        ]}
        toolbar={can('payroll.run.prepare') ? <Button onClick={() => setCreating(true)}><Plus aria-hidden />{t('pay.newRun')}</Button> : null}
      />
      <FormDialog
        open={creating}
        onOpenChange={setCreating}
        title={t('pay.newRun')}
        schema={runSchema}
        defaults={{ payrollPeriodId: null, runType: 'REGULAR', description: '', accountingDate: null }}
        onSubmit={(v) =>
          api.post('/payroll-runs', null, { body: { payrollPeriodId: v.payrollPeriodId!, runType: v.runType!, description: v.description || undefined, accountingDate: v.accountingDate ?? undefined } })
        }
        onDone={(r) => void navigate({ to: '/c/$companyId/payroll/runs/$runId', params: { companyId, runId: (r as Schemas['RunResponse']).run!.id! } })}
      >
        <EntityField name="payrollPeriodId" label={t('pay.period')} source={entities.payrollPeriod} required />
        <SelectField name="runType" label={t('pay.runType')} options={enumOptions(enums.runType)} required />
        <TextField name="description" label={t('common.description')} />
        <DateField name="accountingDate" label={t('pay.accountingDate')} />
      </FormDialog>
    </div>
  );
}

const runRoute = getRouteApi('/_authed/c/$companyId/payroll/runs/$runId');
const paidSchema = z.object({ bankAccountId: zf.id(), paymentDate: zf.date() });

/**
 * A payroll run: calculate (an async job, polled), approve by a second user (SoD), post to the
 * ledger, mark as paid; payslips, summary and register.
 */
export function PayrollRunPage() {
  const { runId } = runRoute.useParams();
  const { api, companyId, can } = useCompany();
  const [paying, setPaying] = useState(false);
  const query = useCompanyQuery(['payroll-runs', runId], (c, signal) => c.get('/payroll-runs/{runId}', { runId }, { signal }), {
    // While the calculation job runs, poll with backoff (API.md §14: 1 s, capped).
    refetchInterval: (q) => (q.state.data?.run?.status === 'CALCULATING' ? Math.min(10_000, 2_000 * 2 ** Math.min(q.state.dataUpdateCount, 2)) : false),
  });
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data?.run) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const r = query.data.run;
  const issues = query.data.issues ?? [];
  const actions: DocAction<Run>[] = [
    {
      id: 'calculate',
      label: t('pay.calculate'),
      primary: true,
      when: inStatus('DRAFT', 'CALCULATED'),
      permissions: ['payroll.run.prepare'],
      run: (c, x) => c.post('/payroll-runs/{runId}/calculate', { runId: x.id! }, { ifMatch: x.version }),
    },
    {
      id: 'approve',
      label: t('pay.approve'),
      primary: true,
      when: inStatus('CALCULATED'),
      permissions: ['payroll.run.approve'],
      confirm: { title: t('pay.approve'), description: t('pay.approveSod') },
      run: (c, x, ctx) => c.post('/payroll-runs/{runId}/approve', { runId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.APPROVED'),
    },
    {
      id: 'post',
      label: t('pay.post'),
      primary: true,
      when: inStatus('APPROVED'),
      permissions: ['payroll.run.post'],
      confirm: { title: t('pay.postConfirm') },
      run: (c, x, ctx) => c.post('/payroll-runs/{runId}/post', { runId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    { id: 'paid', label: t('pay.markPaid'), primary: true, when: inStatus('POSTED'), permissions: ['payroll.run.pay'], open: () => setPaying(true) },
    {
      id: 'unapprove',
      label: t('pay.unapprove'),
      when: inStatus('APPROVED'),
      permissions: ['payroll.run.approve'],
      confirm: { title: t('pay.unapprove') },
      run: (c, x, ctx) => c.post('/payroll-runs/{runId}/unapprove', { runId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
    },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT', 'CALCULATED'),
      permissions: ['payroll.run.prepare'],
      confirm: { title: t('doc.cancel'), destructive: true },
      run: (c, x) => c.post('/payroll-runs/{runId}/cancel', { runId: x.id! }, { ifMatch: x.version }),
    },
  ];
  return (
    <div className="space-y-4">
      <PageHeader
        breadcrumbs={<BackLink to={`/c/${companyId}/payroll/runs`} label={t('pay.runsTitle')} />}
        title={r.number ?? `${t('pay.run')} · ${enumLabel(r.status)}`}
        badge={<StatusBadge status={r.status} />}
        description={<>{enumLabel(r.runType)} · <EntityName source={entities.payrollPeriod} id={r.periodId} /></>}
        actions={
          <>
            <AuditHistoryButton entityType="payroll_run" entityId={r.id} />
            {(r.status === 'POSTED' || r.status === 'PAID') && can('payroll.run.pay') ? (
              <Button variant="outline" onClick={() => void download(api.url('/payroll-runs/{runId}/bank-file', { runId }), `${r.number}-bank-file.csv`).catch(notify.error)}>
                <Download aria-hidden />
                {t('pay.bankFile')}
              </Button>
            ) : null}
            <DocumentActions doc={r} actions={actions} />
          </>
        }
      />
      {r.status === 'CALCULATING' ? (
        <Alert role="status">
          <AlertDescription>{t('pay.calculating')}</AlertDescription>
        </Alert>
      ) : null}
      <Section>
        <DetailList
          columns={4}
          items={[
            { label: t('pay.employees'), value: <Text value={r.employeeCount} /> },
            { label: t('pay.gross'), value: <Money value={r.grossTotal} currency={r.currencyCode} /> },
            { label: t('pay.deductions'), value: <Money value={r.deductionTotal} currency={r.currencyCode} /> },
            { label: t('pay.net'), value: <Money value={r.netTotal} currency={r.currencyCode} className="font-semibold" /> },
            { label: t('pay.contributions'), value: <Money value={r.employerContributionTotal} currency={r.currencyCode} /> },
            { label: t('pay.accountingDate'), value: <DateText value={r.accountingDate} /> },
            r.calculatedAt ? { label: t('enums.CALCULATED'), value: <DateTimeText value={r.calculatedAt} /> } : null,
            r.approvedAt ? { label: t('enums.APPROVED'), value: <DateTimeText value={r.approvedAt} /> } : null,
            r.postedAt ? { label: t('enums.POSTED'), value: <DateTimeText value={r.postedAt} /> } : null,
            r.paidAt ? { label: t('enums.PAID'), value: <DateText value={r.paymentDate} /> } : null,
          ]}
        />
      </Section>
      {issues.length > 0 ? (
        <Section title={t('pay.issues')}>
          <ul className="space-y-1 text-sm">
            {issues.map((i) => (
              <li key={i.id}>
                <EntityName source={entities.employee} id={i.employeeId} /> — <Code>{i.code}</Code> {i.message}
              </li>
            ))}
          </ul>
        </Section>
      ) : null}
      <Tabs defaultValue="payslips">
        <TabsList>
          {can('payroll.payslip.read') ? <TabsTrigger value="payslips">{t('pay.payslips')}</TabsTrigger> : null}
          {can('payroll.report.read') ? <TabsTrigger value="summary">{t('pay.summary')}</TabsTrigger> : null}
        </TabsList>
        {can('payroll.payslip.read') ? (
          <TabsContent value="payslips">
            <PayslipsTable key={r.status} runId={runId} status={r.status} currency={r.currencyCode} />
          </TabsContent>
        ) : null}
        {can('payroll.report.read') ? (
          <TabsContent value="summary">
            <RunSummary key={r.status} runId={runId} status={r.status} currency={r.currencyCode} />
          </TabsContent>
        ) : null}
      </Tabs>
      <FormDialog
        open={paying}
        onOpenChange={setPaying}
        title={t('pay.markPaid')}
        schema={paidSchema}
        defaults={{ bankAccountId: null, paymentDate: todayIso() }}
        success={t('enums.PAID')}
        onSubmit={(v, key) => api.post('/payroll-runs/{runId}/mark-paid', { runId }, { body: { bankAccountId: v.bankAccountId!, paymentDate: v.paymentDate! }, ifMatch: r.version, idempotencyKey: key })}
      >
        <EntityField name="bankAccountId" label={t('pay.bankAccount')} source={entities.bankAccount} required />
        <DateField name="paymentDate" label={t('pay.paymentDate')} required />
      </FormDialog>
    </div>
  );
}

function PayslipsTable({ runId, status, currency }: { runId: string; status?: string; currency?: string }) {
  const { companyId } = useCompany();
  return (
    <DataTable<Schemas['Payslip']>
      id={`payslips-${runId}-${status}`}
      fetchPage={(c, query, signal) => c.get('/payroll-runs/{runId}/payslips', { runId }, { query, signal })}
      rowKey={(p) => p.id!}
      searchable={false}
      defaultSort="employeeNumber"
      filters={[{ kind: 'entity', key: 'departmentId', label: t('common.department'), source: entities.department }]}
      columns={[
        {
          id: 'employee',
          header: t('nav.employees'),
          sortKey: 'employeeNumber',
          cell: (p) => (
            <Link to="/c/$companyId/payroll/payslips/$payslipId" params={{ companyId, payslipId: p.id! }} data-row-link className="font-medium text-primary hover:underline">
              {p.employeeNumber} — {p.employeeName}
            </Link>
          ),
        },
        { id: 'department', header: t('common.department'), hideBelow: 'md', cell: (p) => <EntityName source={entities.department} id={p.departmentId} /> },
        { id: 'days', header: t('pay.daysPaid'), align: 'right', hideBelow: 'sm', cell: (p) => `${p.daysPaid ?? ''} / ${p.daysInPeriod ?? ''}` },
        { id: 'gross', header: t('pay.gross'), align: 'right', cell: (p) => <Money value={p.grossAmount} currency={currency} showCurrency={false} /> },
        { id: 'deductions', header: t('pay.deductions'), align: 'right', hideBelow: 'md', cell: (p) => <Money value={p.deductionAmount} currency={currency} showCurrency={false} /> },
        { id: 'net', header: t('pay.net'), align: 'right', cell: (p) => <Money value={p.netAmount} currency={currency} showCurrency={false} /> },
      ]}
    />
  );
}

function RunSummary({ runId, status, currency }: { runId: string; status?: string; currency?: string }) {
  const summary = useCompanyQuery(['payroll-runs', runId, 'summary', status], (c, signal) => c.get('/payroll-runs/{runId}/summary', { runId }, { signal }));
  if (summary.isLoading) return <LoadingState />;
  if (summary.isError) return <ErrorState error={summary.error} />;
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Section title={t('pay.byDepartment')} bodyClassName="p-0">
        <LinesTable<Schemas['DepartmentTotal']>
          lines={summary.data?.departments ?? []}
          rowKey={(d) => d.departmentId ?? 'none'}
          columns={[
            { id: 'department', header: t('common.department'), cell: (d) => <EntityName source={entities.department} id={d.departmentId} /> },
            { id: 'employees', header: t('pay.employees'), align: 'right', cell: (d) => d.employees },
            { id: 'gross', header: t('pay.gross'), align: 'right', cell: (d) => <Money value={d.gross} currency={currency} showCurrency={false} /> },
            { id: 'net', header: t('pay.net'), align: 'right', cell: (d) => <Money value={d.net} currency={currency} showCurrency={false} /> },
          ]}
        />
      </Section>
      <Section title={t('pay.byComponent')} bodyClassName="p-0">
        <LinesTable<Schemas['ComponentTotal']>
          lines={summary.data?.components ?? []}
          rowKey={(c) => c.componentId!}
          columns={[
            { id: 'component', header: t('pay.components'), cell: (c) => <Code>{c.componentCode}</Code> },
            { id: 'kind', header: t('pay.kind'), cell: (c) => enumLabel(c.kind) },
            { id: 'amount', header: t('pay.amount'), align: 'right', cell: (c) => <Money value={c.amount} currency={currency} showCurrency={false} /> },
          ]}
        />
      </Section>
    </div>
  );
}

// --- Payslips ---------------------------------------------------------------------------------

/** A payslip with its lines; the PDF exists once the run is posted (downloads are audited). */
export function PayslipView({ detail, pdfUrl }: { detail: Schemas['Detail']; pdfUrl?: string }) {
  const p = detail.payslip!;
  return (
    <div className="space-y-4">
      <Section
        actions={
          pdfUrl && p.fileId ? (
            <Button variant="outline" size="sm" onClick={() => void download(pdfUrl, `payslip-${p.employeeNumber}.pdf`).catch(notify.error)}>
              <Download aria-hidden />
              {t('pay.downloadPdf')}
            </Button>
          ) : null
        }
        title={`${p.employeeNumber} — ${p.employeeName}`}
      >
        <DetailList
          columns={4}
          items={[
            { label: t('pay.position'), value: <Text value={p.positionTitle} /> },
            { label: t('common.department'), value: <EntityName source={entities.department} id={p.departmentId} /> },
            { label: t('pay.daysPaid'), value: `${p.daysPaid ?? ''} / ${p.daysInPeriod ?? ''}` },
            { label: t('pay.baseAmount'), value: <Money value={p.baseAmount} currency={p.currencyCode} /> },
            { label: t('pay.gross'), value: <Money value={p.grossAmount} currency={p.currencyCode} /> },
            { label: t('pay.taxableGross'), value: <Money value={p.taxableGross} currency={p.currencyCode} /> },
            { label: t('pay.deductions'), value: <Money value={p.deductionAmount} currency={p.currencyCode} /> },
            { label: t('pay.net'), value: <Money value={p.netAmount} currency={p.currencyCode} className="font-semibold" /> },
          ]}
        />
      </Section>
      <Section title={t('pay.components')} bodyClassName="p-0">
        <LinesTable<Schemas['PayslipLine']>
          lines={detail.lines ?? []}
          rowKey={(l) => `${l.componentId}-${l.sequence}`}
          columns={[
            { id: 'component', header: t('pay.components'), cell: (l) => `${l.componentCode} — ${l.componentName}` },
            { id: 'kind', header: t('pay.kind'), cell: (l) => enumLabel(l.kind) },
            { id: 'qty', header: t('pay.quantity'), align: 'right', hideBelow: 'sm', cell: (l) => <Quantity value={l.quantity} /> },
            { id: 'rate', header: t('pay.rate'), align: 'right', hideBelow: 'sm', cell: (l) => <Quantity value={l.rate} /> },
            { id: 'amount', header: t('pay.amount'), align: 'right', cell: (l) => <Money value={l.amount} currency={p.currencyCode} showCurrency={false} /> },
          ]}
        />
      </Section>
    </div>
  );
}

const payslipRoute = getRouteApi('/_authed/c/$companyId/payroll/payslips/$payslipId');

export function PayslipPage() {
  const { payslipId } = payslipRoute.useParams();
  const { api, companyId } = useCompany();
  const query = useCompanyQuery(['payslips', payslipId], (c, signal) => c.get('/payslips/{payslipId}', { payslipId }, { signal }));
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  return (
    <div className="space-y-4">
      <PageHeader
        breadcrumbs={<BackLink to={`/c/${companyId}/payroll/runs/${query.data.payslip?.runId}`} label={t('pay.run')} />}
        title={t('pay.payslip')}
        badge={<StatusBadge status={query.data.runStatus} />}
      />
      <PayslipView detail={query.data} pdfUrl={api.url('/payslips/{payslipId}/pdf', { payslipId })} />
    </div>
  );
}

// --- Self-service payslips --------------------------------------------------------------------

export function MyPayslipsPage() {
  const { companyId } = useCompany();
  const query = useCompanyQuery(['me', 'payslips'], (c, signal) => c.get('/me/payslips', null, { signal }), { retry: false });
  return (
    <div className="space-y-4">
      <PageHeader title={t('pay.myPayslips')} />
      <Section bodyClassName="p-0">
        {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : (query.data?.data ?? []).length === 0 ? <EmptyState /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('pay.payslip')}</TableHead>
                  <TableHead className="text-right">{t('pay.gross')}</TableHead>
                  <TableHead className="text-right">{t('pay.net')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(query.data?.data ?? []).map((p) => (
                  <TableRow key={p.id}>
                    <TableCell>
                      <Link to="/c/$companyId/me/payslips/$payslipId" params={{ companyId, payslipId: p.id! }} className="font-medium text-primary hover:underline">
                        {t('pay.payslip')} · {t('pay.daysPaid')} {p.daysPaid}/{p.daysInPeriod}
                      </Link>
                    </TableCell>
                    <TableCell className="text-right"><Money value={p.grossAmount} currency={p.currencyCode} /></TableCell>
                    <TableCell className="text-right"><Money value={p.netAmount} currency={p.currencyCode} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
    </div>
  );
}

const myPayslipRoute = getRouteApi('/_authed/c/$companyId/me/payslips/$payslipId');

export function MyPayslipPage() {
  const { payslipId } = myPayslipRoute.useParams();
  const { api, companyId } = useCompany();
  const query = useCompanyQuery(['me', 'payslips', payslipId], (c, signal) => c.get('/me/payslips/{payslipId}', { payslipId }, { signal }));
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} />;
  return (
    <div className="space-y-4">
      <PageHeader breadcrumbs={<BackLink to={`/c/${companyId}/me/payslips`} label={t('pay.myPayslips')} />} title={t('pay.payslip')} />
      <PayslipView detail={query.data} pdfUrl={api.url('/me/payslips/{payslipId}/pdf', { payslipId })} />
    </div>
  );
}
