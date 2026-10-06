import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useController, useFormContext } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { DocumentActions, type DocAction } from '@/components/document/actions';
import { ErrorState, LoadingState, EmptyState } from '@/components/feedback/states';
import { DateField, EntityField, FieldGrid, FieldShell, SelectField, TextField } from '@/components/form/fields';
import { DateInput } from '@/components/form/inputs';
import { zf } from '@/components/form/schema';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { addDaysIso, formatTime, startOfMonthIso, todayIso } from '@/lib/format';

type Attendance = Schemas['Attendance'];

export function minutesText(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return '';
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')} h`;
}

/** A time of day on a work date, sent as a UTC instant (check-in and check-out). */
function TimeOnDate({ name, label }: { name: 'checkIn' | 'checkOut'; label: string }) {
  const { control, watch } = useFormContext<RecordValues>();
  const { field, fieldState } = useController({ control, name });
  const date = watch('workDate');
  const value = field.value ? new Date(field.value) : null;
  const text = value ? `${String(value.getHours()).padStart(2, '0')}:${String(value.getMinutes()).padStart(2, '0')}` : '';
  return (
    <FieldShell id={`att-${name}`} label={label} error={fieldState.error?.message}>
      <Input
        id={`att-${name}`}
        type="time"
        value={text}
        onChange={(e) => {
          if (!e.target.value || !date) return field.onChange(null);
          const [h, m] = e.target.value.split(':').map(Number);
          const [y, mo, d] = date.split('-').map(Number);
          field.onChange(new Date(y!, mo! - 1, d!, h!, m!).toISOString());
        }}
      />
    </FieldShell>
  );
}

const recordSchema = z.object({
  employeeId: zf.id(),
  workDate: zf.date(),
  status: zf.id(),
  checkIn: z.string().nullable(),
  checkOut: z.string().nullable(),
  note: zf.optionalText(500),
});
type RecordValues = z.infer<typeof recordSchema>;

export function RecordAttendanceDialog({
  open,
  onOpenChange,
  employeeId,
  record,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  employeeId?: string;
  record?: Attendance;
}) {
  const { api } = useCompany();
  return (
    <FormDialog<RecordValues>
      open={open}
      onOpenChange={onOpenChange}
      title={t('hr.recordAttendance')}
      schema={recordSchema}
      defaults={{
        employeeId: record?.employeeId ?? employeeId ?? null,
        workDate: record?.workDate ?? todayIso(),
        status: record?.status ?? 'PRESENT',
        checkIn: record?.checkIn ?? null,
        checkOut: record?.checkOut ?? null,
        note: record?.note ?? '',
      }}
      success={t('common.saved')}
      onSubmit={(v) =>
        api.put('/employees/{employeeId}/attendance/{workDate}', { employeeId: v.employeeId!, workDate: v.workDate! }, {
          body: { status: v.status!, checkIn: v.checkIn ?? undefined, checkOut: v.checkOut ?? undefined, note: v.note || undefined },
        })
      }
    >
      <EntityField name="employeeId" label={t('nav.employees')} source={entities.employee} required disabled={!!employeeId || !!record} />
      <FieldGrid>
        <DateField name="workDate" label={t('hr.workDate')} required disabled={!!record} />
        <SelectField name="status" label={t('common.status')} options={enumOptions(enums.attendanceStatus)} required />
        <TimeOnDate name="checkIn" label={t('hr.checkIn')} />
        <TimeOnDate name="checkOut" label={t('hr.checkOut')} />
      </FieldGrid>
      <TextField name="note" label={t('common.notes')} />
    </FormDialog>
  );
}

function attendanceActions(): DocAction<Attendance>[] {
  return [
    {
      id: 'clear',
      label: t('hr.clearAttendance'),
      variant: 'destructive',
      permissions: ['hr.attendance.manage'],
      confirm: { title: t('hr.clearAttendance'), destructive: true },
      run: (api, a) => api.delete('/employees/{employeeId}/attendance/{workDate}', { employeeId: a.employeeId!, workDate: a.workDate! }),
      success: t('common.deleted'),
    },
  ];
}

/** Daily attendance records (ADR-039), optionally of one employee. */
export function AttendanceTable({ employeeId }: { employeeId?: string }) {
  const { can } = useCompany();
  const [recording, setRecording] = useState<{ record?: Attendance } | null>(null);
  return (
    <>
      <DataTable<Attendance>
        id={employeeId ? `attendance-${employeeId}` : 'attendance'}
        fetchPage={(api, query, signal) => api.get('/attendance', null, { query, signal })}
        rowKey={(a) => a.id!}
        defaultSort="-workDate"
        searchable={false}
        fixedFilters={employeeId ? { employeeId } : undefined}
        filters={[
          ...(employeeId ? [] : [{ kind: 'entity' as const, key: 'employeeId', label: t('nav.employees'), source: entities.employee }]),
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.attendanceStatus] },
          { kind: 'dateRange', field: 'workDate', label: t('hr.workDate') },
        ]}
        columns={[
          { id: 'date', header: t('hr.workDate'), sortKey: 'workDate', cell: (a) => <DateText value={a.workDate} /> },
          ...(employeeId ? [] : [{ id: 'employee', header: t('nav.employees'), cell: (a: Attendance) => <EntityName source={entities.employee} id={a.employeeId} /> }]),
          { id: 'status', header: t('common.status'), cell: (a) => <StatusBadge status={a.status} /> },
          { id: 'in', header: t('hr.checkIn'), hideBelow: 'sm', cell: (a) => formatTime(a.checkIn) || '—' },
          { id: 'out', header: t('hr.checkOut'), hideBelow: 'sm', cell: (a) => formatTime(a.checkOut) || '—' },
          { id: 'worked', header: t('hr.worked'), align: 'right', cell: (a) => minutesText(a.workedMinutes) || '—' },
          { id: 'source', header: t('fields.source'), hideBelow: 'md', cell: (a) => enumLabel(a.source) },
          { id: 'note', header: t('common.notes'), hideBelow: 'lg', cell: (a) => <Text value={a.note} /> },
          ...(can('hr.attendance.manage')
            ? [
                {
                  id: 'actions',
                  header: <span className="sr-only">{t('common.actions')}</span>,
                  align: 'right' as const,
                  cell: (a: Attendance) => (
                    <div className="flex justify-end gap-1">
                      <Button size="sm" variant="ghost" onClick={() => setRecording({ record: a })}>{t('common.edit')}</Button>
                      <DocumentActions doc={a} actions={attendanceActions()} />
                    </div>
                  ),
                },
              ]
            : []),
        ]}
        toolbar={
          can('hr.attendance.manage') ? (
            <Button onClick={() => setRecording({})}>
              <Plus aria-hidden />
              {t('hr.recordAttendance')}
            </Button>
          ) : null
        }
      />
      {recording ? (
        <RecordAttendanceDialog open onOpenChange={(open) => !open && setRecording(null)} employeeId={employeeId} record={recording.record} />
      ) : null}
    </>
  );
}

export function AttendancePage() {
  const [from, setFrom] = useState(startOfMonthIso());
  const [to, setTo] = useState(todayIso());
  const summary = useCompanyQuery(['attendance-summary', from, to], (api, signal) =>
    api.get('/attendance/summary', null, { query: { from, to }, signal }),
  );
  const statuses = [...enums.attendanceStatus];
  return (
    <div className="space-y-4">
      <PageHeader title={t('hr.attendanceTitle')} />
      <AttendanceTable />
      <Section
        title={t('hr.summary')}
        actions={
          <div className="flex flex-wrap items-end gap-2">
            <div className="space-y-1">
              <Label htmlFor="sum-from" className="text-xs">{t('common.from')}</Label>
              <DateInput id="sum-from" value={from} onChange={(v) => setFrom(v ?? addDaysIso(todayIso(), -30))} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="sum-to" className="text-xs">{t('common.to')}</Label>
              <DateInput id="sum-to" value={to} onChange={(v) => setTo(v ?? todayIso())} />
            </div>
          </div>
        }
        bodyClassName="p-0"
      >
        {summary.isLoading ? <LoadingState /> : summary.isError ? <ErrorState error={summary.error} /> : (summary.data?.employees ?? []).length === 0 ? <EmptyState /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('nav.employees')}</TableHead>
                  {statuses.map((s) => (
                    <TableHead key={s} className="text-right">{enumLabel(s)}</TableHead>
                  ))}
                  <TableHead className="text-right">{t('hr.worked')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(summary.data?.employees ?? []).map((row) => {
                  const days = (row.days ?? {}) as Record<string, number>;
                  return (
                    <TableRow key={row.employeeId}>
                      <TableCell>
                        <span className="font-mono text-xs">{row.employeeNumber}</span> {row.name}
                      </TableCell>
                      {statuses.map((s) => (
                        <TableCell key={s} className="text-right tabular">{days[s] ?? 0}</TableCell>
                      ))}
                      <TableCell className="text-right tabular">{minutesText(row.workedMinutes)}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
    </div>
  );
}
