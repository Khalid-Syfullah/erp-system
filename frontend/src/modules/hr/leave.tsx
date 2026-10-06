import { Plus } from 'lucide-react';
import { useState } from 'react';
import { z } from 'zod';
import type { CompanyApi, Query, Schemas } from '@/api/client';
import type { Page } from '@/api/list';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Quantity, Text } from '@/components/common/values';
import { DataTable, type Column, type FilterDef } from '@/components/data/data-table';
import { EntityName, EntityPicker } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, DateField, DecimalField, EntityField, FieldGrid, IntegerField, SelectField, TextareaField, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, t } from '@/i18n';
import { enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { EmployeeLink } from './employees';

type LeaveRequest = Schemas['LeaveRequest'];
type Balance = Schemas['Balance'];

const requestSchema = z.object({
  employeeId: zf.optionalId(),
  leaveTypeId: zf.id(),
  startDate: zf.date(),
  endDate: zf.date(),
  halfDay: z.boolean(),
  reason: zf.optionalText(500),
});
type RequestValues = z.infer<typeof requestSchema>;

/** Where a leave request is managed: HR (all), the employee (own) or a manager (team, HR-3). */
export type LeaveScope = 'hr' | 'own' | 'team';

function requestBody(v: RequestValues) {
  return {
    employeeId: v.employeeId ?? undefined,
    leaveTypeId: v.leaveTypeId!,
    startDate: v.startDate!,
    endDate: v.endDate!,
    halfDay: v.halfDay || undefined,
    reason: v.reason || undefined,
  };
}

function decision(label: string, title: string, run: DocAction<LeaveRequest>['run'], when: DocAction<LeaveRequest>['when'], variant?: 'destructive'): DocAction<LeaveRequest> {
  return {
    id: label,
    label,
    when,
    variant,
    confirm: { title, reason: 'optional', reasonLabel: t('hr.decisionNote'), destructive: variant === 'destructive' },
    run,
    success: t('common.saved'),
  };
}

/** The leave state machine's actions per scope (draft → submitted → approved/rejected; cancel). */
function leaveActions(scope: LeaveScope, edit: (r: LeaveRequest) => void): DocAction<LeaveRequest>[] {
  const note = (reason: string) => ({ note: reason || undefined });
  if (scope === 'team') {
    return [
      decision(t('hr.approve'), t('hr.approveConfirm'), (api, r, c) => api.post('/me/team/leave-requests/{requestId}/approve', { requestId: r.id! }, { body: note(c.reason), ifMatch: r.version }), inStatus('SUBMITTED')),
      decision(t('hr.reject'), t('hr.rejectConfirm'), (api, r, c) => api.post('/me/team/leave-requests/{requestId}/reject', { requestId: r.id! }, { body: note(c.reason), ifMatch: r.version }), inStatus('SUBMITTED'), 'destructive'),
    ];
  }
  if (scope === 'own') {
    return [
      { id: 'edit', label: t('common.edit'), when: inStatus('DRAFT'), open: edit },
      { id: 'submit', label: t('hr.submitRequest'), when: inStatus('DRAFT'), run: (api, r) => api.post('/me/leave-requests/{requestId}/submit', { requestId: r.id! }, { ifMatch: r.version }), success: t('hr.submitted') },
      decision(t('hr.cancelRequest'), t('hr.cancelConfirm'), (api, r, c) => api.post('/me/leave-requests/{requestId}/cancel', { requestId: r.id! }, { body: note(c.reason), ifMatch: r.version }), inStatus('SUBMITTED', 'APPROVED'), 'destructive'),
      {
        id: 'delete',
        label: t('hr.deleteDraft'),
        variant: 'destructive',
        when: inStatus('DRAFT'),
        confirm: { title: t('hr.deleteDraft'), destructive: true },
        run: (api, r) => api.delete('/me/leave-requests/{requestId}', { requestId: r.id! }, { ifMatch: r.version }),
        success: t('common.deleted'),
      },
    ];
  }
  const perm = ['hr.leave.approve'];
  return [
    { id: 'edit', label: t('common.edit'), when: inStatus('DRAFT'), permissions: perm, open: edit },
    { id: 'submit', label: t('hr.submitRequest'), when: inStatus('DRAFT'), permissions: perm, run: (api, r) => api.post('/leave-requests/{requestId}/submit', { requestId: r.id! }, { ifMatch: r.version }), success: t('hr.submitted') },
    { ...decision(t('hr.approve'), t('hr.approveConfirm'), (api, r, c) => api.post('/leave-requests/{requestId}/approve', { requestId: r.id! }, { body: note(c.reason), ifMatch: r.version }), inStatus('SUBMITTED')), permissions: perm },
    { ...decision(t('hr.reject'), t('hr.rejectConfirm'), (api, r, c) => api.post('/leave-requests/{requestId}/reject', { requestId: r.id! }, { body: note(c.reason), ifMatch: r.version }), inStatus('SUBMITTED'), 'destructive'), permissions: perm },
    { ...decision(t('hr.cancelRequest'), t('hr.cancelConfirm'), (api, r, c) => api.post('/leave-requests/{requestId}/cancel', { requestId: r.id! }, { body: note(c.reason), ifMatch: r.version }), inStatus('SUBMITTED', 'APPROVED'), 'destructive'), permissions: perm },
    {
      id: 'delete',
      label: t('hr.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: perm,
      confirm: { title: t('hr.deleteDraft'), destructive: true },
      run: (api, r) => api.delete('/leave-requests/{requestId}', { requestId: r.id! }, { ifMatch: r.version }),
      success: t('common.deleted'),
    },
  ];
}

const listPath: Record<LeaveScope, (api: CompanyApi, query: Query, signal: AbortSignal) => Promise<Page<LeaveRequest>>> = {
  hr: (api, query, signal) => api.get('/leave-requests', null, { query, signal }),
  own: (api, query, signal) => api.get('/me/leave-requests', null, { query, signal }),
  team: (api, query, signal) => api.get('/me/team/leave-requests', null, { query, signal }),
};

/** Leave requests with their state actions, for HR, the employee or the manager. */
export function LeaveRequestsTable({ scope, leaveTypes }: { scope: LeaveScope; leaveTypes?: Balance[] }) {
  const { api, can } = useCompany();
  const [editing, setEditing] = useState<{ request?: LeaveRequest } | null>(null);
  const typeName = (id?: string) => leaveTypes?.find((b) => b.leaveTypeId === id)?.leaveTypeName;
  const filters: FilterDef[] = [
    ...(scope === 'own' ? [] : [{ kind: 'entity' as const, key: 'employeeId', label: t('nav.employees'), source: entities.employee }]),
    { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.leaveStatus] },
    { kind: 'dateRange', field: 'startDate', label: t('hr.startDate') },
  ];
  const columns: Column<LeaveRequest>[] = [
    ...(scope === 'own'
      ? []
      : [{ id: 'employee', header: t('nav.employees'), cell: (r: LeaveRequest) => (scope === 'hr' ? <EmployeeLink id={r.employeeId}><EntityName source={entities.employee} id={r.employeeId} /></EmployeeLink> : <EntityName source={entities.employee} id={r.employeeId} />) }]),
    { id: 'type', header: t('hr.leaveType'), cell: (r) => (leaveTypes ? <Text value={typeName(r.leaveTypeId)} /> : <EntityName source={entities.leaveType} id={r.leaveTypeId} />) },
    { id: 'start', header: t('hr.startDate'), sortKey: 'startDate', cell: (r) => <DateText value={r.startDate} /> },
    { id: 'end', header: t('hr.endDate'), cell: (r) => <DateText value={r.endDate} /> },
    { id: 'days', header: t('hr.days'), align: 'right', cell: (r) => <Quantity value={r.days} /> },
    { id: 'status', header: t('common.status'), cell: (r) => <StatusBadge status={r.status} /> },
    { id: 'submitted', header: t('hr.submitted'), hideBelow: 'lg', cell: (r) => <DateTimeText value={r.submittedAt} /> },
    { id: 'reason', header: t('hr.reason'), hideBelow: 'md', cell: (r) => <Text value={r.reason} /> },
    {
      id: 'actions',
      header: <span className="sr-only">{t('common.actions')}</span>,
      align: 'right',
      cell: (r) => (
        <div className="flex justify-end gap-1">
          <DocumentActions doc={r} actions={leaveActions(scope, (x) => setEditing({ request: x })).map((a) => ({ ...a, primary: a.id === 'submit' || a.id === t('hr.approve') }))} />
        </div>
      ),
    },
  ];
  const canCreate = scope === 'own' || (scope === 'hr' && can('hr.leave.approve'));
  return (
    <>
      <DataTable<LeaveRequest>
        id={`leave-requests-${scope}`}
        fetchPage={listPath[scope]}
        rowKey={(r) => r.id!}
        defaultSort="-startDate"
        searchable={false}
        filters={filters}
        columns={columns}
        toolbar={
          canCreate ? (
            <Button onClick={() => setEditing({})}>
              <Plus aria-hidden />
              {t('hr.newLeaveRequest')}
            </Button>
          ) : null
        }
      />
      {editing ? (
        <FormDialog<RequestValues>
          open
          onOpenChange={(open) => !open && setEditing(null)}
          title={editing.request ? t('common.edit') : t('hr.newLeaveRequest')}
          schema={requestSchema.refine((v) => scope !== 'hr' || !!v.employeeId, { path: ['employeeId'], message: t('forms.required') })}
          defaults={{
            employeeId: editing.request?.employeeId ?? null,
            leaveTypeId: editing.request?.leaveTypeId ?? null,
            startDate: editing.request?.startDate ?? todayIso(),
            endDate: editing.request?.endDate ?? todayIso(),
            halfDay: false,
            reason: editing.request?.reason ?? '',
          }}
          success={t('common.saved')}
          onSubmit={(v) => {
            const body = requestBody(scope === 'own' ? { ...v, employeeId: null } : v);
            if (editing.request) {
              const requestId = editing.request.id!;
              return scope === 'own'
                ? api.put('/me/leave-requests/{requestId}', { requestId }, { body, ifMatch: editing.request.version })
                : api.put('/leave-requests/{requestId}', { requestId }, { body, ifMatch: editing.request.version });
            }
            return scope === 'own' ? api.post('/me/leave-requests', null, { body }) : api.post('/leave-requests', null, { body });
          }}
        >
          {scope === 'hr' ? <EntityField name="employeeId" label={t('nav.employees')} source={entities.employee} required disabled={!!editing.request} /> : null}
          {leaveTypes ? (
            <SelectField name="leaveTypeId" label={t('hr.leaveType')} required options={leaveTypes.map((b) => ({ value: b.leaveTypeId!, label: b.leaveTypeName ?? b.leaveTypeCode ?? '' }))} />
          ) : (
            <EntityField name="leaveTypeId" label={t('hr.leaveType')} source={entities.leaveType} required />
          )}
          <FieldGrid>
            <DateField name="startDate" label={t('hr.startDate')} required />
            <DateField name="endDate" label={t('hr.endDate')} required />
          </FieldGrid>
          <CheckboxField name="halfDay" label={t('hr.halfDay')} />
          <TextareaField name="reason" label={t('hr.reason')} />
        </FormDialog>
      ) : null}
    </>
  );
}

export function LeaveRequestsPage() {
  return (
    <div className="space-y-4">
      <PageHeader title={t('hr.leaveRequestsTitle')} />
      <LeaveRequestsTable scope="hr" />
    </div>
  );
}

/** Leave balances per type for a year (HR-2): accrued, carried forward, taken, pending, available. */
export function BalancesTable({ balances, isLoading, error }: { balances: Balance[] | undefined; isLoading: boolean; error: unknown }) {
  if (isLoading) return <LoadingState />;
  if (error) return <ErrorState error={error} />;
  if (!balances || balances.length === 0) return <EmptyState className="py-6" />;
  const cols: [keyof Balance, string][] = [
    ['accrued', t('hr.accrued')],
    ['carriedForward', t('hr.carriedForward')],
    ['taken', t('hr.taken')],
    ['adjusted', t('hr.adjusted')],
    ['expired', t('hr.expired')],
    ['balance', t('hr.balance')],
    ['pending', t('hr.pending')],
    ['available', t('hr.available')],
  ];
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t('hr.leaveType')}</TableHead>
            {cols.map(([key, label]) => (
              <TableHead key={key} className="text-right">{label}</TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {balances.map((b) => (
            <TableRow key={b.leaveTypeId}>
              <TableCell>{b.leaveTypeName}</TableCell>
              {cols.map(([key]) => (
                <TableCell key={key} className={key === 'available' ? 'text-right font-semibold' : 'text-right'}>
                  <Quantity value={b[key] as string} />
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

const adjustSchema = z.object({ employeeId: zf.id(), leaveTypeId: zf.id(), year: zf.integer(), days: zf.decimal(), note: zf.text(500) });

export function LeaveBalancesPage() {
  const { api, can } = useCompany();
  const [employeeId, setEmployeeId] = useState<string | null>(null);
  const [year, setYear] = useState(new Date().getFullYear());
  const [adjusting, setAdjusting] = useState(false);
  const [accruing, setAccruing] = useState(false);
  const balances = useCompanyQuery(
    ['leave-balances', employeeId, year],
    (c, signal) => c.get('/leave-balances', null, { query: { employeeId: employeeId!, year }, signal }),
    { enabled: !!employeeId },
  );
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('hr.balancesTitle')}
        actions={
          can('hr.leave.adjust') ? (
            <>
              <Button variant="outline" onClick={() => setAccruing(true)}>{t('hr.runAccruals')}</Button>
              <Button onClick={() => setAdjusting(true)}>{t('hr.adjust')}</Button>
            </>
          ) : null
        }
      />
      <Section>
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-full space-y-1.5 sm:w-80">
            <Label id="bal-employee">{t('nav.employees')}</Label>
            <EntityPicker source={entities.employee} value={employeeId} onChange={setEmployeeId} aria-labelledby="bal-employee" />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="bal-year">{t('hr.year')}</Label>
            <Input id="bal-year" type="number" value={year} onChange={(e) => setYear(Number(e.target.value) || new Date().getFullYear())} className="w-28" />
          </div>
        </div>
      </Section>
      <Section title={t('hr.balancesTitle')} bodyClassName="p-0">
        {employeeId ? (
          <BalancesTable balances={balances.data?.data} isLoading={balances.isLoading} error={balances.error} />
        ) : (
          <EmptyState title={t('hr.chooseEmployee')} className="py-6" />
        )}
      </Section>
      {employeeId ? (
        <Section title={t('hr.ledger')} bodyClassName="p-0">
          <DataTable<Schemas['HrLedgerEntry']>
            id="leave-ledger"
            fetchPage={(c, query, signal) => c.get('/leave-ledger', null, { query, signal })}
            rowKey={(e) => e.id!}
            searchable={false}
            fixedFilters={{ employeeId, leaveYear: String(year) }}
            filters={[{ kind: 'enum', key: 'entryType', label: t('hr.entryType'), values: [...enums.leaveLedgerType] }]}
            defaultSort="-createdAt"
            columns={[
              { id: 'created', header: t('common.date'), sortKey: 'createdAt', cell: (e) => <DateTimeText value={e.createdAt} /> },
              { id: 'type', header: t('hr.leaveType'), cell: (e) => <EntityName source={entities.leaveType} id={e.leaveTypeId} /> },
              { id: 'entry', header: t('hr.entryType'), cell: (e) => enumLabel(e.entryType) },
              { id: 'days', header: t('hr.days'), align: 'right', cell: (e) => <Quantity value={e.days} /> },
              { id: 'note', header: t('common.notes'), hideBelow: 'md', cell: (e) => <Text value={e.note} /> },
            ]}
          />
        </Section>
      ) : null}
      <FormDialog
        open={adjusting}
        onOpenChange={setAdjusting}
        title={t('hr.adjust')}
        description={t('hr.adjustText')}
        schema={adjustSchema}
        defaults={{ employeeId, leaveTypeId: null, year, days: null, note: '' }}
        success={t('common.saved')}
        onSubmit={(v, key) =>
          api.post('/leave-ledger/adjustments', null, {
            body: { employeeId: v.employeeId!, leaveTypeId: v.leaveTypeId!, year: v.year, days: v.days!, note: v.note },
            idempotencyKey: key,
          })
        }
      >
        <EntityField name="employeeId" label={t('nav.employees')} source={entities.employee} required />
        <EntityField name="leaveTypeId" label={t('hr.leaveType')} source={entities.leaveType} required />
        <FieldGrid>
          <IntegerField name="year" label={t('hr.year')} required />
          <DecimalField name="days" label={t('hr.days')} required />
        </FieldGrid>
        <TextField name="note" label={t('common.notes')} required />
      </FormDialog>
      <FormDialog
        open={accruing}
        onOpenChange={setAccruing}
        title={t('hr.runAccruals')}
        schema={z.object({ asOf: zf.date() })}
        defaults={{ asOf: todayIso() }}
        onSubmit={(v) => api.post('/leave-accruals', null, { body: { asOf: v.asOf! } })}
        onDone={(result) => {
          const r = result as Schemas['Result'];
          notify.success(t('hr.accrualsDone', { accruals: r.accruals, carryForwards: r.carryForwards, expiries: r.expiries }));
        }}
      >
        <DateField name="asOf" label={t('hr.asOf')} required />
      </FormDialog>
    </div>
  );
}
