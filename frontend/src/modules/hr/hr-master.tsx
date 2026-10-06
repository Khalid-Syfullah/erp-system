import { useState } from 'react';
import { Controller, useFormContext } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { BooleanBadge } from '@/components/common/status-badge';
import { Code, DateText, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { CheckboxField, DateField, DecimalField, EntityField, FieldGrid, IntegerField, SelectField, TextField } from '@/components/form/fields';
import { DateInput } from '@/components/form/inputs';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { activationActions, MasterDataPage } from '@/components/master/master-data-page';
import { SettingsPage } from '@/components/master/settings-page';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, t, type MessageKey } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { EmployeeLink } from './employees';

const activeBadge = (active: boolean | undefined) => <BooleanBadge value={active !== false} yes={t('common.active')} no={t('common.inactive')} />;

// --- Positions --------------------------------------------------------------------------------

type Position = Schemas['PositionResponse'];
const positionSchema = z.object({ code: zf.text(20), title: zf.text(100), departmentId: zf.optionalId(), grade: zf.optionalText(20) });

export function PositionsPage() {
  return (
    <MasterDataPage<Position, z.infer<typeof positionSchema>>
      title={t('hr.positionsTitle')}
      managePermission="hr.position.manage"
      table={{
        id: 'positions',
        fetchPage: (api, query, signal) => api.get('/positions', null, { query, signal }),
        rowKey: (p) => p.id!,
        defaultSort: 'code',
        filters: [
          { kind: 'entity', key: 'departmentId', label: t('common.department'), source: entities.department },
          { kind: 'boolean', key: 'isActive', label: t('common.active') },
        ],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (p) => <Code>{p.code}</Code> },
          { id: 'title', header: t('hr.title'), sortKey: 'title', cell: (p) => p.title },
          { id: 'department', header: t('common.department'), hideBelow: 'sm', cell: (p) => <EntityName source={entities.department} id={p.departmentId} /> },
          { id: 'grade', header: t('hr.grade'), hideBelow: 'md', cell: (p) => <Text value={p.grade} /> },
          { id: 'active', header: t('common.status'), cell: (p) => activeBadge(p.isActive) },
        ],
      }}
      form={{
        schema: positionSchema,
        values: (p) => ({ code: p?.code ?? '', title: p?.title ?? '', departmentId: p?.departmentId ?? null, grade: p?.grade ?? '' }),
        createTitle: t('hr.newPosition'),
        editTitle: (p) => t('hr.editPosition', { code: p.code }),
        fields: (mode) => (
          <FieldGrid>
            <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
            <TextField name="title" label={t('hr.title')} required />
            <EntityField name="departmentId" label={t('common.department')} source={entities.department} />
            <TextField name="grade" label={t('hr.grade')} />
          </FieldGrid>
        ),
        create: (api, v) => api.post('/positions', null, { body: compact(v) as Schemas['CreatePositionRequest'] }),
        update: (api, p, v, initial) => {
          const { code: _c, ...rest } = v;
          const { code: _i, ...before } = initial;
          return api.patch('/positions/{positionId}', { positionId: p.id! }, { body: mergePatch(before, rest), ifMatch: p.version });
        },
      }}
      rowActions={activationActions<Position>(
        (p) => p.isActive !== false,
        (p) => ({
          activate: (api) => api.post('/positions/{positionId}/activate', { positionId: p.id! }, { ifMatch: p.version }),
          deactivate: (api) => api.post('/positions/{positionId}/deactivate', { positionId: p.id! }, { ifMatch: p.version }),
        }),
        'hr.position.manage',
      )}
    />
  );
}

// --- Leave types ------------------------------------------------------------------------------

type LeaveType = Schemas['LeaveType'];
const leaveTypeSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  isPaid: z.boolean(),
  annualEntitlementDays: zf.decimal(),
  accrualMethod: zf.id(),
  maxCarryForwardDays: zf.optionalDecimal(),
  allowNegativeBalance: z.boolean(),
});

export function LeaveTypesPage() {
  return (
    <MasterDataPage<LeaveType, z.infer<typeof leaveTypeSchema>>
      title={t('hr.leaveTypesTitle')}
      managePermission="hr.leave.configure"
      table={{
        id: 'leave-types',
        fetchPage: (api, query, signal) => api.get('/leave-types', null, { query, signal }),
        rowKey: (l) => l.id!,
        defaultSort: 'code',
        filters: [{ kind: 'boolean', key: 'isActive', label: t('common.active') }],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (l) => <Code>{l.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (l) => l.name },
          { id: 'entitlement', header: t('hr.entitlement'), align: 'right', cell: (l) => <Quantity value={l.annualEntitlementDays} /> },
          { id: 'accrual', header: t('hr.accrualMethod'), hideBelow: 'sm', cell: (l) => enumLabel(l.accrualMethod) },
          { id: 'carry', header: t('hr.carryForward'), align: 'right', hideBelow: 'md', cell: (l) => <Quantity value={l.maxCarryForwardDays} /> },
          { id: 'paid', header: t('hr.paid'), hideBelow: 'md', cell: (l) => (l.paid ? t('common.yes') : t('common.no')) },
          { id: 'active', header: t('common.status'), cell: (l) => activeBadge(l.active) },
        ],
      }}
      form={{
        schema: leaveTypeSchema,
        values: (l) => ({
          code: l?.code ?? '',
          name: l?.name ?? '',
          isPaid: l?.paid ?? true,
          annualEntitlementDays: l?.annualEntitlementDays ?? null,
          accrualMethod: l?.accrualMethod ?? 'ANNUAL',
          maxCarryForwardDays: l?.maxCarryForwardDays ?? null,
          allowNegativeBalance: !!l?.allowNegativeBalance,
        }),
        createTitle: t('hr.newLeaveType'),
        editTitle: (l) => t('hr.editLeaveType', { code: l.code }),
        fields: (mode) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
              <DecimalField name="annualEntitlementDays" label={t('hr.entitlement')} required />
              <SelectField name="accrualMethod" label={t('hr.accrualMethod')} options={enumOptions(enums.accrualMethod)} required disabled={mode === 'edit'} />
              <DecimalField name="maxCarryForwardDays" label={t('hr.carryForward')} />
            </FieldGrid>
            <CheckboxField name="isPaid" label={t('hr.paid')} />
            <CheckboxField name="allowNegativeBalance" label={t('hr.allowNegative')} />
          </>
        ),
        create: (api, v) => api.post('/leave-types', null, { body: compact(v) as Schemas['LeaveTypeRequest'] }),
        update: (api, l, v, initial) => {
          const { code: _c, accrualMethod: _a, ...rest } = v;
          const { code: _ic, accrualMethod: _ia, ...before } = initial;
          return api.patch('/leave-types/{typeId}', { typeId: l.id! }, { body: mergePatch(before, rest), ifMatch: l.version });
        },
      }}
      rowActions={[
        {
          id: 'deactivate',
          label: t('common.deactivate'),
          when: (l) => l.active !== false,
          permissions: ['hr.leave.configure'],
          run: (api, l) => api.patch('/leave-types/{typeId}', { typeId: l.id! }, { body: { isActive: false }, ifMatch: l.version }),
        },
        {
          id: 'activate',
          label: t('common.activate'),
          when: (l) => l.active === false,
          permissions: ['hr.leave.configure'],
          run: (api, l) => api.patch('/leave-types/{typeId}', { typeId: l.id! }, { body: { isActive: true }, ifMatch: l.version }),
        },
      ]}
    />
  );
}

// --- Public holidays --------------------------------------------------------------------------

type Holiday = Schemas['Holiday'];
const holidaySchema = z.object({ date: zf.date(), name: zf.text(100), branchId: zf.optionalId() });

export function HolidaysPage() {
  return (
    <MasterDataPage<Holiday, z.infer<typeof holidaySchema>>
      title={t('hr.holidaysTitle')}
      managePermission="hr.leave.configure"
      table={{
        id: 'holidays',
        fetchPage: (api, query, signal) => api.get('/public-holidays', null, { query, signal }),
        rowKey: (h) => h.id!,
        defaultSort: 'date',
        searchable: false,
        filters: [
          { kind: 'dateRange', field: 'date', label: t('common.date') },
          { kind: 'entity', key: 'branchId', label: t('common.branch'), source: entities.branch },
        ],
        columns: [
          { id: 'date', header: t('common.date'), sortKey: 'date', cell: (h) => <DateText value={h.date} /> },
          { id: 'name', header: t('common.name'), cell: (h) => h.name },
          { id: 'branch', header: t('common.branch'), cell: (h) => (h.branchId ? <EntityName source={entities.branch} id={h.branchId} /> : t('hr.allBranches')) },
        ],
      }}
      form={{
        schema: holidaySchema,
        values: (h) => ({ date: h?.date ?? null, name: h?.name ?? '', branchId: h?.branchId ?? null }),
        createTitle: t('hr.newHoliday'),
        editTitle: () => t('hr.editHoliday'),
        fields: (mode) => (
          <>
            <DateField name="date" label={t('common.date')} required />
            <TextField name="name" label={t('common.name')} required />
            <EntityField name="branchId" label={t('common.branch')} source={entities.branch} disabled={mode === 'edit'} />
          </>
        ),
        create: (api, v) => api.post('/public-holidays', null, { body: compact(v) as Schemas['HolidayRequest'] }),
        update: (api, h, v) =>
          api.patch('/public-holidays/{holidayId}', { holidayId: h.id! }, { body: mergePatch({ date: h.date, name: h.name }, { date: v.date, name: v.name }), ifMatch: h.version }),
      }}
      rowActions={[
        {
          id: 'delete',
          label: t('hr.deleteHoliday'),
          variant: 'destructive',
          permissions: ['hr.leave.configure'],
          confirm: { title: t('hr.deleteHoliday'), destructive: true },
          run: (api, h) => api.delete('/public-holidays/{holidayId}', { holidayId: h.id! }),
          success: t('common.deleted'),
        },
      ]}
    />
  );
}

// --- Department heads -------------------------------------------------------------------------

type Head = Schemas['HeadResponse'];
const headSchema = z.object({ departmentId: zf.id(), employeeId: zf.id(), effectiveFrom: zf.date(), effectiveTo: zf.optionalDate() });

export function DepartmentHeadsPage() {
  const { api, can } = useCompany();
  const [ending, setEnding] = useState<Head | null>(null);
  return (
    <>
      <MasterDataPage<Head, z.infer<typeof headSchema>>
        title={t('hr.departmentHeadsTitle')}
        managePermission="hr.employee.manage"
        table={{
          id: 'department-heads',
          fetchPage: (c, query, signal) => c.get('/department-heads', null, { query, signal }),
          rowKey: (h) => h.id!,
          defaultSort: 'effectiveFrom',
          searchable: false,
          filters: [
            { kind: 'entity', key: 'departmentId', label: t('common.department'), source: entities.department },
            { kind: 'entity', key: 'employeeId', label: t('nav.employees'), source: entities.employee },
          ],
          columns: [
            { id: 'department', header: t('common.department'), cell: (h) => <EntityName source={entities.department} id={h.departmentId} /> },
            { id: 'employee', header: t('nav.employees'), cell: (h) => <EmployeeLink id={h.employeeId}><EntityName source={entities.employee} id={h.employeeId} /></EmployeeLink> },
            { id: 'from', header: t('hr.effectiveFrom'), sortKey: 'effectiveFrom', cell: (h) => <DateText value={h.effectiveFrom} /> },
            { id: 'to', header: t('hr.effectiveTo'), cell: (h) => <DateText value={h.effectiveTo} /> },
          ],
        }}
        form={{
          schema: headSchema,
          values: () => ({ departmentId: null, employeeId: null, effectiveFrom: todayIso(), effectiveTo: null }),
          createTitle: t('hr.newHead'),
          editTitle: () => t('hr.endHead'),
          fields: () => (
            <>
              <EntityField name="departmentId" label={t('common.department')} source={entities.department} required />
              <EntityField name="employeeId" label={t('nav.employees')} source={entities.employee} required />
              <FieldGrid>
                <DateField name="effectiveFrom" label={t('hr.effectiveFrom')} required />
                <DateField name="effectiveTo" label={t('hr.effectiveTo')} />
              </FieldGrid>
            </>
          ),
          create: (c, v) => c.post('/department-heads', null, { body: compact(v) as Schemas['CreateHeadRequest'] }),
        }}
        rowActions={
          can('hr.employee.manage')
            ? [{ id: 'end', label: t('hr.endHead'), when: (h) => !h.effectiveTo, open: (h) => setEnding(h) }]
            : []
        }
      />
      <FormDialog
        open={ending !== null}
        onOpenChange={(open) => !open && setEnding(null)}
        title={t('hr.endHead')}
        schema={z.object({ effectiveTo: zf.date() })}
        defaults={{ effectiveTo: todayIso() }}
        success={t('common.saved')}
        onSubmit={(v) => api.patch('/department-heads/{headId}', { headId: ending!.id! }, { body: { effectiveTo: v.effectiveTo }, ifMatch: ending!.version })}
      >
        <DateField name="effectiveTo" label={t('hr.effectiveTo')} required />
      </FormDialog>
    </>
  );
}

// --- HR settings ------------------------------------------------------------------------------

const settingsSchema = z.object({ weekendDays: z.array(z.number()), standardWorkMinutes: zf.integer() });
type SettingsValues = z.infer<typeof settingsSchema>;

function WeekendDays() {
  const { control } = useFormContext<SettingsValues>();
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">{t('hr.weekendDays')}</legend>
      <Controller
        control={control}
        name="weekendDays"
        render={({ field }) => (
          <div className="flex flex-wrap gap-4">
            {[1, 2, 3, 4, 5, 6, 7].map((day) => (
              <label key={day} className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={field.value.includes(day)}
                  onCheckedChange={(checked) =>
                    field.onChange(checked === true ? [...field.value, day].sort() : field.value.filter((d) => d !== day))
                  }
                />
                {t(`hr.weekday${day}` as MessageKey)}
              </label>
            ))}
          </div>
        )}
      />
    </fieldset>
  );
}

export function HrSettingsPage() {
  return (
    <SettingsPage<Schemas['HrSettings'], SettingsValues>
      title={t('hr.settingsTitle')}
      queryKey="hr"
      load={async (api, signal) => {
        const data = await api.get('/settings/hr', null, { signal });
        return { data, version: data.version ?? null };
      }}
      schema={settingsSchema}
      values={(d) => ({ weekendDays: [...(d.weekendDays ?? [])], standardWorkMinutes: d.standardWorkMinutes ?? 480 })}
      fields={() => (
        <>
          <WeekendDays />
          <IntegerField name="standardWorkMinutes" label={t('hr.standardWorkMinutes')} min={60} max={1440} required />
        </>
      )}
      save={(api, v, version) => api.put('/settings/hr', null, { body: v, ifMatch: version })}
    />
  );
}

// --- Organization on a date -------------------------------------------------------------------

/** Who works where on a date (effective-dated assignments, API.md §8.2) and the headcount by unit. */
export function OrganizationPage() {
  const [asOf, setAsOf] = useState(todayIso());
  const headcount = useCompanyQuery(['headcount', asOf], (api, signal) => api.get('/hr-reports/headcount', null, { query: { asOf }, signal }));
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('hr.organizationTitle')}
        actions={
          <div className="flex items-center gap-2">
            <Label htmlFor="org-as-of">{t('hr.asOf')}</Label>
            <DateInput id="org-as-of" value={asOf} onChange={(v) => setAsOf(v ?? todayIso())} />
          </div>
        }
      />
      <Section title={`${t('hr.headcount')}${headcount.data ? ` · ${headcount.data.total}` : ''}`} bodyClassName="p-0">
        {headcount.isLoading ? <LoadingState /> : headcount.isError ? <ErrorState error={headcount.error} /> : (headcount.data?.rows ?? []).length === 0 ? <EmptyState /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('common.branch')}</TableHead>
                  <TableHead>{t('common.department')}</TableHead>
                  <TableHead className="text-right">{t('hr.employees')}</TableHead>
                  <TableHead className="text-right">{t('hr.fte')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(headcount.data?.rows ?? []).map((r, i) => (
                  <TableRow key={i}>
                    <TableCell><EntityName source={entities.branch} id={r.branchId} /></TableCell>
                    <TableCell><EntityName source={entities.department} id={r.departmentId} /></TableCell>
                    <TableCell className="text-right tabular">{r.employees}</TableCell>
                    <TableCell className="text-right"><Quantity value={r.fte} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
      <DataTable<Schemas['HrAssignmentResponse']>
        id="employment-assignments"
        fetchPage={(api, query, signal) => api.get('/employment-assignments', null, { query, signal })}
        rowKey={(a) => a.id!}
        params={{ asOf }}
        searchable={false}
        defaultSort="effectiveFrom"
        filters={[
          { kind: 'entity', key: 'branchId', label: t('common.branch'), source: entities.branch },
          { kind: 'entity', key: 'departmentId', label: t('common.department'), source: entities.department },
          { kind: 'entity', key: 'managerEmployeeId', label: t('hr.manager'), source: entities.employee },
        ]}
        columns={[
          { id: 'employee', header: t('nav.employees'), cell: (a) => <EmployeeLink id={a.employeeId}><EntityName source={entities.employee} id={a.employeeId} /></EmployeeLink> },
          { id: 'branch', header: t('common.branch'), hideBelow: 'sm', cell: (a) => <EntityName source={entities.branch} id={a.branchId} /> },
          { id: 'department', header: t('common.department'), cell: (a) => <EntityName source={entities.department} id={a.departmentId} /> },
          { id: 'position', header: t('hr.position'), hideBelow: 'md', cell: (a) => <EntityName source={entities.position} id={a.positionId} /> },
          { id: 'manager', header: t('hr.manager'), hideBelow: 'lg', cell: (a) => <EntityName source={entities.employee} id={a.managerEmployeeId} /> },
          { id: 'from', header: t('hr.effectiveFrom'), sortKey: 'effectiveFrom', hideBelow: 'md', cell: (a) => <DateText value={a.effectiveFrom} /> },
        ]}
      />
    </div>
  );
}

