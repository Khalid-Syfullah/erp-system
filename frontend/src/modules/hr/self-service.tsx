import { zodResolver } from '@hookform/resolvers/zod';
import { LogIn, LogOut } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { isApiError } from '@/api/errors';
import { useCompanyMutation, useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { Code, DateText, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { FieldGrid, Form, TextField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { t } from '@/i18n';
import { formatTime, todayIso } from '@/lib/format';
import { minutesText } from './attendance';
import { employeeName } from './employees';
import { BalancesTable, LeaveRequestsTable } from './leave';

/** Self-service pages answer 404 NOT_AN_EMPLOYEE for users without an employee link. */
function SelfServiceError({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (isApiError(error) && error.status === 404) return <EmptyState title={t('hr.notEmployee')} />;
  return <ErrorState error={error} onRetry={onRetry} />;
}

const profileSchema = z.object({
  preferredName: zf.optionalText(100),
  personalEmail: zf.optionalEmail(),
  phone: zf.optionalText(30),
});

export function MyProfilePage() {
  const query = useCompanyQuery(['me', 'employee'], (api, signal) => api.get('/me/employee', null, { signal }), { retry: false });
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <SelfServiceError error={query.error} onRetry={() => query.refetch()} />;
  return <MyProfile employee={query.data} />;
}

function MyProfile({ employee: e }: { employee: Schemas['EmployeeResponse'] }) {
  const { api } = useCompany();
  const initial = { preferredName: e.preferredName ?? '', personalEmail: e.personalEmail ?? '', phone: e.phone ?? '' };
  const form = useForm<z.infer<typeof profileSchema>>({ resolver: zodResolver(profileSchema), values: initial });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    await api.patch('/me/employee', null, { body: mergePatch(initial, v), ifMatch: e.version });
    notify.success(t('common.saved'));
  });
  return (
    <div className="max-w-3xl space-y-4">
      <PageHeader title={t('hr.myProfile')} description={<Code>{e.employeeNumber}</Code>} badge={<StatusBadge status={e.status} />} />
      <Section>
        <DetailList
          items={[
            { label: t('common.name'), value: employeeName(e) },
            { label: t('hr.workEmail'), value: <Text value={e.workEmail} /> },
            { label: t('hr.hireDate'), value: <DateText value={e.hireDate} /> },
          ]}
        />
      </Section>
      <Section>
        <Form form={form} onSubmit={submit}>
          <FieldGrid>
            <TextField name="preferredName" label={t('hr.preferredName')} />
            <TextField name="personalEmail" label={t('hr.personalEmail')} type="email" />
            <TextField name="phone" label={t('fields.phone')} type="tel" />
          </FieldGrid>
          <FormProblem {...problem} />
          <Button type="submit" disabled={form.formState.isSubmitting || !form.formState.isDirty}>{t('common.save')}</Button>
        </Form>
      </Section>
    </div>
  );
}

export function MyLeavePage() {
  const [year, setYear] = useState(new Date().getFullYear());
  const balances = useCompanyQuery(['me', 'leave-balances', year], (api, signal) => api.get('/me/leave-balances', null, { query: { year }, signal }), { retry: false });
  if (balances.isError && isApiError(balances.error) && balances.error.status === 404) return <SelfServiceError error={balances.error} />;
  return (
    <div className="space-y-4">
      <PageHeader title={t('hr.myLeave')} />
      <Section
        title={t('hr.balancesTitle')}
        actions={
          <div className="flex items-center gap-2">
            <Label htmlFor="my-year">{t('hr.year')}</Label>
            <Input id="my-year" type="number" value={year} onChange={(e) => setYear(Number(e.target.value) || new Date().getFullYear())} className="w-24" />
          </div>
        }
        bodyClassName="p-0"
      >
        <BalancesTable balances={balances.data?.data} isLoading={balances.isLoading} error={balances.error} />
      </Section>
      <LeaveRequestsTable scope="own" leaveTypes={balances.data?.data} />
    </div>
  );
}

export function MyAttendancePage() {
  const today = todayIso();
  const todayRecord = useCompanyQuery(['me', 'attendance', today], (api, signal) =>
    api.get('/me/attendance', null, { query: { 'filter[workDate]': today, limit: 1 }, signal }),
    { retry: false },
  );
  const clock = useCompanyMutation<'in' | 'out'>({
    mutationFn: (api, which) => (which === 'in' ? api.post('/me/attendance/clock-in', null) : api.post('/me/attendance/clock-out', null)),
    onSuccess: (_, which) => notify.success(which === 'in' ? t('hr.clockedIn') : t('hr.clockedOut')),
    onError: notify.error,
  });
  if (todayRecord.isError && isApiError(todayRecord.error) && todayRecord.error.status === 404) return <SelfServiceError error={todayRecord.error} />;
  const record = todayRecord.data?.data?.[0];
  return (
    <div className="space-y-4">
      <PageHeader title={t('hr.myAttendance')} />
      <Section>
        <div className="flex flex-wrap items-center gap-4">
          <div className="min-w-48 flex-1 space-y-1">
            <div className="text-sm text-muted-foreground"><DateText value={today} /></div>
            <div className="text-sm">
              {t('hr.checkIn')}: <strong>{formatTime(record?.checkIn) || '—'}</strong> · {t('hr.checkOut')}: <strong>{formatTime(record?.checkOut) || '—'}</strong>
            </div>
          </div>
          <Button size="lg" onClick={() => clock.mutate('in')} disabled={clock.isPending || !!record?.checkIn}>
            <LogIn aria-hidden />
            {t('hr.clockIn')}
          </Button>
          <Button size="lg" variant="outline" onClick={() => clock.mutate('out')} disabled={clock.isPending || !record?.checkIn || !!record?.checkOut}>
            <LogOut aria-hidden />
            {t('hr.clockOut')}
          </Button>
        </div>
      </Section>
      <DataTable
        id="my-attendance"
        fetchPage={(api, query, signal) => api.get('/me/attendance', null, { query, signal })}
        rowKey={(a) => a.id!}
        searchable={false}
        defaultSort="-workDate"
        filters={[{ kind: 'dateRange', field: 'workDate', label: t('hr.workDate') }]}
        columns={[
          { id: 'date', header: t('hr.workDate'), sortKey: 'workDate', cell: (a) => <DateText value={a.workDate} /> },
          { id: 'status', header: t('common.status'), cell: (a) => <StatusBadge status={a.status} /> },
          { id: 'in', header: t('hr.checkIn'), cell: (a) => formatTime(a.checkIn) || '—' },
          { id: 'out', header: t('hr.checkOut'), cell: (a) => formatTime(a.checkOut) || '—' },
          { id: 'worked', header: t('hr.worked'), align: 'right', cell: (a) => minutesText(a.workedMinutes) || '—' },
        ]}
      />
    </div>
  );
}

export function MyTeamPage() {
  const team = useCompanyQuery(['me', 'team'], (api, signal) => api.get('/me/team', null, { signal }), { retry: false });
  if (team.isError) return <SelfServiceError error={team.error} onRetry={() => team.refetch()} />;
  return (
    <div className="space-y-4">
      <PageHeader title={t('hr.myTeam')} />
      <Section bodyClassName="p-0">
        {team.isLoading ? <LoadingState /> : (team.data?.data ?? []).length === 0 ? <EmptyState /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('hr.employeeNumber')}</TableHead>
                  <TableHead>{t('common.name')}</TableHead>
                  <TableHead>{t('hr.workEmail')}</TableHead>
                  <TableHead>{t('common.status')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(team.data?.data ?? []).map((m) => (
                  <TableRow key={m.employeeId}>
                    <TableCell><Code>{m.employeeNumber}</Code></TableCell>
                    <TableCell>{m.name}</TableCell>
                    <TableCell><Text value={m.workEmail} /></TableCell>
                    <TableCell><StatusBadge status={m.status} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
      <h2 className="text-base font-semibold">{t('hr.teamRequests')}</h2>
      <LeaveRequestsTable scope="team" />
    </div>
  );
}
