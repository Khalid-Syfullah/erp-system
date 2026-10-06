import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Eye, Link2, Pencil, Plus, Trash2, Unlink } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { download, type Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { Code, DateText, Money, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AttachmentsPanel } from '@/components/document/attachments-panel';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, type DocAction } from '@/components/document/actions';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import {
  DateField,
  DecimalField,
  EntityField,
  FieldGrid,
  Form,
  SelectField,
  TextField,
} from '@/components/form/fields';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Drawer } from '@/components/overlay/drawer';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { AttendanceTable } from './attendance';

type Employee = Schemas['EmployeeResponse'];

export function employeeName(e: Pick<Employee, 'firstName' | 'lastName' | 'preferredName'>): string {
  return `${e.preferredName || e.firstName} ${e.lastName}`;
}

export function EmployeeLink({ id, children }: { id: string | undefined; children: React.ReactNode }) {
  const { companyId } = useCompany();
  if (!id) return <>{children}</>;
  return (
    <Link to="/c/$companyId/hr/employees/$employeeId" params={{ companyId, employeeId: id }} data-row-link className="font-medium text-primary hover:underline">
      {children}
    </Link>
  );
}

export function EmployeesPage() {
  const { can } = useCompany();
  const [creating, setCreating] = useState(false);
  const create = can('hr.employee.manage') ? (
    <Button onClick={() => setCreating(true)}>
      <Plus aria-hidden />
      {t('hr.newEmployee')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('hr.employeesTitle')} />
      <DataTable<Employee>
        id="employees"
        fetchPage={(api, query, signal) => api.get('/employees', null, { query, signal })}
        rowKey={(e) => e.id!}
        defaultSort="employeeNumber"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.employeeStatus] },
          { kind: 'dateRange', field: 'hireDate', label: t('hr.hireDate') },
        ]}
        columns={[
          { id: 'number', header: t('hr.employeeNumber'), sortKey: 'employeeNumber', cell: (e) => <EmployeeLink id={e.id}><Code>{e.employeeNumber}</Code></EmployeeLink> },
          { id: 'name', header: t('common.name'), sortKey: 'lastName', cell: (e) => employeeName(e) },
          { id: 'email', header: t('hr.workEmail'), hideBelow: 'lg', cell: (e) => <Text value={e.workEmail} /> },
          { id: 'department', header: t('common.department'), hideBelow: 'md', cell: (e) => <EntityName source={entities.department} id={e.currentAssignment?.departmentId} /> },
          { id: 'position', header: t('hr.position'), hideBelow: 'lg', cell: (e) => <EntityName source={entities.position} id={e.currentAssignment?.positionId} /> },
          { id: 'hire', header: t('hr.hireDate'), sortKey: 'hireDate', hideBelow: 'sm', cell: (e) => <DateText value={e.hireDate} /> },
          { id: 'status', header: t('common.status'), cell: (e) => <StatusBadge status={e.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
      {creating ? <CreateEmployeeDrawer onClose={() => setCreating(false)} /> : null}
    </div>
  );
}

const assignmentFields = {
  branchId: zf.id(),
  departmentId: zf.id(),
  positionId: zf.optionalId(),
  managerEmployeeId: zf.optionalId(),
  employmentType: zf.optionalId(),
  fte: zf.optionalDecimal(),
  effectiveFrom: zf.optionalDate(),
};

const createSchema = z.object({
  employeeNumber: zf.text(20),
  firstName: zf.text(100),
  lastName: zf.text(100),
  preferredName: zf.optionalText(100),
  workEmail: zf.optionalEmail(),
  hireDate: zf.date(),
  ...assignmentFields,
});

function AssignmentInputs({ withFrom = true }: { withFrom?: boolean }) {
  return (
    <FieldGrid>
      <EntityField name="branchId" label={t('common.branch')} source={entities.branch} required />
      <EntityField name="departmentId" label={t('common.department')} source={entities.department} required />
      <EntityField name="positionId" label={t('hr.position')} source={entities.position} />
      <EntityField name="managerEmployeeId" label={t('hr.manager')} source={entities.employee} />
      <SelectField name="employmentType" label={t('hr.employmentType')} options={enumOptions(enums.employmentType)} allowEmpty />
      <DecimalField name="fte" label={t('hr.fte')} />
      {withFrom ? <DateField name="effectiveFrom" label={t('hr.effectiveFrom')} /> : null}
    </FieldGrid>
  );
}

function CreateEmployeeDrawer({ onClose }: { onClose: () => void }) {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const form = useForm<z.infer<typeof createSchema>>({
    resolver: zodResolver(createSchema),
    defaultValues: {
      employeeNumber: '', firstName: '', lastName: '', preferredName: '', workEmail: '', hireDate: todayIso(),
      branchId: null, departmentId: null, positionId: null, managerEmployeeId: null, employmentType: 'FULL_TIME', fte: '1', effectiveFrom: null,
    },
  });
  const { submit, ...problem } = useSubmit(
    form,
    async (v) => {
      const { branchId, departmentId, positionId, managerEmployeeId, employmentType, fte, effectiveFrom, ...person } = v;
      const employee = await api.post('/employees', null, {
        body: {
          ...(compact(person) as Schemas['CreateEmployeeRequest']),
          initialAssignment: compact({ branchId, departmentId, positionId, managerEmployeeId, employmentType, fte, effectiveFrom }) as Schemas['AssignmentRequest'],
        },
      });
      notify.success(t('common.created'));
      await navigate({ to: '/c/$companyId/hr/employees/$employeeId', params: { companyId, employeeId: employee.id! } });
    },
    {
      fieldMap: Object.fromEntries(Object.keys(assignmentFields).map((k) => [`initialAssignment.${k}`, k])),
    },
  );
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('hr.newEmployee')}
      wide
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form="employee-create">{t('common.create')}</Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id="employee-create">
        <FieldGrid>
          <TextField name="employeeNumber" label={t('hr.employeeNumber')} required />
          <DateField name="hireDate" label={t('hr.hireDate')} required />
          <TextField name="firstName" label={t('hr.firstName')} required />
          <TextField name="lastName" label={t('hr.lastName')} required />
          <TextField name="preferredName" label={t('hr.preferredName')} />
          <TextField name="workEmail" label={t('hr.workEmail')} type="email" />
        </FieldGrid>
        <h3 className="text-sm font-semibold">{t('hr.initialAssignment')}</h3>
        <AssignmentInputs />
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

// --- Employee detail --------------------------------------------------------------------------

const route = getRouteApi('/_authed/c/$companyId/hr/employees/$employeeId');

const terminateSchema = z.object({ terminationDate: zf.date(), reason: zf.text(500) });

export function EmployeePage() {
  const { employeeId } = route.useParams();
  const { api, can } = useCompany();
  const query = useCompanyQuery(['employees', employeeId], (api, signal) => api.get('/employees/{employeeId}', { employeeId }, { signal }));
  const [editing, setEditing] = useState(false);
  const [terminating, setTerminating] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const e = query.data;

  const actions: DocAction<Employee>[] = [
    {
      id: 'activate',
      label: t('hr.activate'),
      primary: true,
      when: (x) => x.status === 'ONBOARDING',
      permissions: ['hr.employee.manage'],
      run: (api, x) => api.post('/employees/{employeeId}/activate', { employeeId: x.id! }, { ifMatch: x.version }),
      success: t('common.saved'),
    },
    {
      id: 'terminate',
      label: t('hr.terminate'),
      variant: 'destructive',
      when: (x) => x.status !== 'TERMINATED',
      permissions: ['hr.employee.terminate'],
      open: () => setTerminating(true),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader
        title={employeeName(e)}
        description={<Code>{e.employeeNumber}</Code>}
        badge={<StatusBadge status={e.status} />}
        actions={
          <>
            <AuditHistoryButton entityType="employee" entityId={e.id} />
            {can('hr.employee.manage') ? (
              <Button variant="outline" onClick={() => setEditing(true)}>
                <Pencil aria-hidden />
                {t('common.edit')}
              </Button>
            ) : null}
            <DocumentActions doc={e} actions={actions} />
          </>
        }
      />
      <Tabs defaultValue="overview">
        <TabsList className="flex-wrap">
          <TabsTrigger value="overview">{t('common.details')}</TabsTrigger>
          <TabsTrigger value="assignments">{t('hr.assignments')}</TabsTrigger>
          {can('payroll.compensation.read') ? <TabsTrigger value="compensation">{t('hr.compensation')}</TabsTrigger> : null}
          {can('hr.employee.manage_bank') ? <TabsTrigger value="bank">{t('hr.bankAccounts')}</TabsTrigger> : null}
          {can('hr.employee.manage') ? <TabsTrigger value="documents">{t('hr.documents')}</TabsTrigger> : null}
          {can('hr.attendance.read') ? <TabsTrigger value="attendance">{t('hr.attendance')}</TabsTrigger> : null}
        </TabsList>
        <TabsContent value="overview" className="space-y-4">
          <EmployeeOverview employee={e} />
        </TabsContent>
        <TabsContent value="assignments">
          <AssignmentsSection employee={e} />
        </TabsContent>
        {can('payroll.compensation.read') ? (
          <TabsContent value="compensation">
            <CompensationSection employee={e} />
          </TabsContent>
        ) : null}
        {can('hr.employee.manage_bank') ? (
          <TabsContent value="bank">
            <EmployeeBankSection employee={e} />
          </TabsContent>
        ) : null}
        {can('hr.employee.manage') ? (
          <TabsContent value="documents">
            <DocumentsSection employee={e} />
          </TabsContent>
        ) : null}
        {can('hr.attendance.read') ? (
          <TabsContent value="attendance">
            <AttendanceTable employeeId={e.id} />
          </TabsContent>
        ) : null}
      </Tabs>
      {editing ? <EditEmployeeDrawer employee={e} onClose={() => setEditing(false)} /> : null}
      <FormDialog
        open={terminating}
        onOpenChange={setTerminating}
        title={t('hr.terminate')}
        description={t('hr.terminateText')}
        schema={terminateSchema}
        defaults={{ terminationDate: todayIso(), reason: '' }}
        destructive
        submitLabel={t('hr.terminate')}
        success={t('common.saved')}
        onSubmit={(v) => api.post('/employees/{employeeId}/terminate', { employeeId: e.id! }, { body: v, ifMatch: e.version })}
      >
        <DateField name="terminationDate" label={t('hr.terminationDate')} required />
        <TextField name="reason" label={t('hr.terminationReason')} required />
      </FormDialog>
    </div>
  );
}

function EmployeeOverview({ employee: e }: { employee: Employee }) {
  const { api, can } = useCompany();
  const [revealed, setRevealed] = useState<Schemas['RevealedResponse'] | null>(null);
  const reveal = async () => {
    try {
      setRevealed(await api.post('/employees/{employeeId}/reveal', { employeeId: e.id! }));
    } catch (error) {
      notify.error(error);
    }
  };
  const address = e.address;
  return (
    <>
      <Section>
        <DetailList
          items={[
            { label: t('hr.firstName'), value: e.firstName },
            { label: t('hr.lastName'), value: e.lastName },
            { label: t('hr.preferredName'), value: <Text value={e.preferredName} /> },
            { label: t('hr.workEmail'), value: <Text value={e.workEmail} /> },
            { label: t('hr.personalEmail'), value: <Text value={e.personalEmail} /> },
            { label: t('fields.phone'), value: <Text value={e.phone} /> },
            { label: t('hr.hireDate'), value: <DateText value={e.hireDate} /> },
            e.terminationDate ? { label: t('hr.terminationDate'), value: <DateText value={e.terminationDate} /> } : null,
            e.terminationReason ? { label: t('hr.terminationReason'), value: e.terminationReason } : null,
            {
              label: t('hr.address'),
              value: address ? <Text value={[address.line1, address.line2, address.postalCode, address.city, address.region, address.countryCode].filter(Boolean).join(', ')} /> : '—',
              wide: true,
            },
          ]}
        />
      </Section>
      <Section
        title={t('hr.reveal')}
        actions={
          can('hr.employee.read_sensitive') && !revealed ? (
            <Button size="sm" variant="outline" onClick={() => void reveal()}>
              <Eye aria-hidden />
              {t('common.reveal')}
            </Button>
          ) : null
        }
      >
        <DetailList
          columns={2}
          items={[
            { label: t('hr.dateOfBirth'), value: revealed ? <DateText value={revealed.dateOfBirth} /> : e.dateOfBirthSet ? t('hr.sensitiveHidden') : '—' },
            { label: t('hr.nationalId'), value: revealed ? <Text value={revealed.nationalId} /> : <Text value={e.nationalIdMasked} /> },
          ]}
        />
      </Section>
      <CurrentAssignment employee={e} />
      <UserLinkSection employee={e} />
    </>
  );
}

function CurrentAssignment({ employee: e }: { employee: Employee }) {
  const a = e.currentAssignment;
  return (
    <Section title={t('hr.current')}>
      {a ? (
        <DetailList
          items={[
            { label: t('common.branch'), value: <EntityName source={entities.branch} id={a.branchId} /> },
            { label: t('common.department'), value: <EntityName source={entities.department} id={a.departmentId} /> },
            { label: t('hr.position'), value: <EntityName source={entities.position} id={a.positionId} /> },
            { label: t('hr.manager'), value: <EntityName source={entities.employee} id={a.managerEmployeeId} /> },
            { label: t('hr.employmentType'), value: enumLabel(a.employmentType) || '—' },
            { label: t('hr.fte'), value: <Quantity value={a.fte} /> },
          ]}
        />
      ) : (
        <EmptyState className="py-4" />
      )}
    </Section>
  );
}

function UserLinkSection({ employee: e }: { employee: Employee }) {
  const { api, can } = useCompany();
  const [linking, setLinking] = useState(false);
  const [unlinking, setUnlinking] = useState(false);
  const canPickUsers = can('auth.role_assignment.manage');
  const users = useCompanyQuery(['role-assignments'], (c, signal) => c.get('/role-assignments', null, { signal }), { enabled: linking && canPickUsers });
  const options = [...new Map((users.data?.data ?? []).map((a) => [a.userId!, `${a.userDisplayName} (${a.userEmail})`])).entries()].map(([value, label]) => ({ value, label }));
  if (!can('hr.employee.manage')) return null;
  return (
    <Section
      title={t('hr.user')}
      actions={
        e.userId ? (
          <Button size="sm" variant="outline" onClick={() => setUnlinking(true)}>
            <Unlink aria-hidden />
            {t('hr.unlinkUser')}
          </Button>
        ) : canPickUsers ? (
          <Button size="sm" variant="outline" onClick={() => setLinking(true)}>
            <Link2 aria-hidden />
            {t('hr.linkUser')}
          </Button>
        ) : null
      }
    >
      <p className="font-mono text-xs">{e.userId ?? '—'}</p>
      <FormDialog
        open={linking}
        onOpenChange={setLinking}
        title={t('hr.linkUser')}
        schema={z.object({ userId: zf.id() })}
        defaults={{ userId: null }}
        success={t('common.saved')}
        onSubmit={(v) => api.put('/employees/{employeeId}/user', { employeeId: e.id! }, { body: { userId: v.userId! }, ifMatch: e.version })}
      >
        <SelectField name="userId" label={t('hr.user')} hint={t('hr.linkUserHint')} options={options} required />
      </FormDialog>
      <ConfirmDialog
        open={unlinking}
        onOpenChange={setUnlinking}
        title={t('hr.unlinkConfirm')}
        destructive
        confirmLabel={t('hr.unlinkUser')}
        onConfirm={() => api.delete('/employees/{employeeId}/user', { employeeId: e.id! }, { ifMatch: e.version })}
      />
    </Section>
  );
}

const editSchema = z.object({
  firstName: zf.text(100),
  lastName: zf.text(100),
  preferredName: zf.optionalText(100),
  workEmail: zf.optionalEmail(),
  personalEmail: zf.optionalEmail(),
  phone: zf.optionalText(30),
  hireDate: zf.date(),
});

function EditEmployeeDrawer({ employee: e, onClose }: { employee: Employee; onClose: () => void }) {
  const { api } = useCompany();
  const initial = {
    firstName: e.firstName ?? '',
    lastName: e.lastName ?? '',
    preferredName: e.preferredName ?? '',
    workEmail: e.workEmail ?? '',
    personalEmail: e.personalEmail ?? '',
    phone: e.phone ?? '',
    hireDate: e.hireDate ?? null,
  };
  const form = useForm<z.infer<typeof editSchema>>({ resolver: zodResolver(editSchema), defaultValues: initial });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    await api.patch('/employees/{employeeId}', { employeeId: e.id! }, { body: mergePatch(initial, v), ifMatch: e.version });
    notify.success(t('common.saved'));
    onClose();
  });
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('common.edit')}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form="employee-edit">{t('common.save')}</Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id="employee-edit">
        <TextField name="firstName" label={t('hr.firstName')} required />
        <TextField name="lastName" label={t('hr.lastName')} required />
        <TextField name="preferredName" label={t('hr.preferredName')} />
        <TextField name="workEmail" label={t('hr.workEmail')} type="email" />
        <TextField name="personalEmail" label={t('hr.personalEmail')} type="email" />
        <TextField name="phone" label={t('fields.phone')} type="tel" />
        <DateField name="hireDate" label={t('hr.hireDate')} required />
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

// --- Assignments ------------------------------------------------------------------------------

const newAssignmentSchema = z.object({ ...assignmentFields, effectiveFrom: zf.date(), effectiveTo: zf.optionalDate() });
const endSchema = z.object({ effectiveTo: zf.date() });

function AssignmentsSection({ employee: e }: { employee: Employee }) {
  const { api, can } = useCompany();
  const employeeId = e.id!;
  const query = useCompanyQuery(['employees', employeeId, 'assignments'], (c, signal) => c.get('/employees/{employeeId}/assignments', { employeeId }, { signal }));
  const [adding, setAdding] = useState(false);
  const [ending, setEnding] = useState<Schemas['HrAssignmentResponse'] | null>(null);
  const [deleting, setDeleting] = useState<Schemas['HrAssignmentResponse'] | null>(null);
  const manage = can('hr.employee.manage');
  const rows = query.data?.data ?? [];
  const today = todayIso();
  return (
    <Section title={t('hr.assignments')} actions={manage ? <Button size="sm" onClick={() => setAdding(true)}><Plus aria-hidden />{t('hr.newAssignment')}</Button> : null} bodyClassName="p-0">
      {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState /> : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('hr.effectiveFrom')}</TableHead>
                <TableHead>{t('hr.effectiveTo')}</TableHead>
                <TableHead>{t('common.branch')}</TableHead>
                <TableHead>{t('common.department')}</TableHead>
                <TableHead className="hidden md:table-cell">{t('hr.position')}</TableHead>
                <TableHead className="hidden lg:table-cell">{t('hr.manager')}</TableHead>
                <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((a) => (
                <TableRow key={a.id}>
                  <TableCell><DateText value={a.effectiveFrom} /></TableCell>
                  <TableCell><DateText value={a.effectiveTo} /></TableCell>
                  <TableCell><EntityName source={entities.branch} id={a.branchId} /></TableCell>
                  <TableCell><EntityName source={entities.department} id={a.departmentId} /></TableCell>
                  <TableCell className="hidden md:table-cell"><EntityName source={entities.position} id={a.positionId} /></TableCell>
                  <TableCell className="hidden lg:table-cell"><EntityName source={entities.employee} id={a.managerEmployeeId} /></TableCell>
                  <TableCell className="text-right">
                    {manage && !a.effectiveTo ? (
                      <Button size="sm" variant="ghost" onClick={() => setEnding(a)}>{t('hr.endAssignment')}</Button>
                    ) : null}
                    {manage && (a.effectiveFrom ?? '') > today ? (
                      <Button size="icon-sm" variant="ghost" onClick={() => setDeleting(a)} aria-label={t('hr.deleteAssignment')}><Trash2 aria-hidden /></Button>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <FormDialog
        open={adding}
        onOpenChange={setAdding}
        title={t('hr.newAssignment')}
        size="lg"
        schema={newAssignmentSchema}
        defaults={{ branchId: e.currentAssignment?.branchId ?? null, departmentId: e.currentAssignment?.departmentId ?? null, positionId: e.currentAssignment?.positionId ?? null, managerEmployeeId: e.currentAssignment?.managerEmployeeId ?? null, employmentType: e.currentAssignment?.employmentType ?? null, fte: e.currentAssignment?.fte ?? null, effectiveFrom: today, effectiveTo: null }}
        success={t('common.saved')}
        onSubmit={(v) => api.post('/employees/{employeeId}/assignments', { employeeId }, { body: compact(v) as Schemas['AssignmentRequest'] })}
      >
        <AssignmentInputs withFrom={false} />
        <FieldGrid>
          <DateField name="effectiveFrom" label={t('hr.effectiveFrom')} required />
          <DateField name="effectiveTo" label={t('hr.effectiveTo')} />
        </FieldGrid>
      </FormDialog>
      <FormDialog
        open={ending !== null}
        onOpenChange={(open) => !open && setEnding(null)}
        title={t('hr.endAssignment')}
        schema={endSchema}
        defaults={{ effectiveTo: today }}
        success={t('common.saved')}
        onSubmit={(v) => api.patch('/employees/{employeeId}/assignments/{assignmentId}', { employeeId, assignmentId: ending!.id! }, { body: { effectiveTo: v.effectiveTo }, ifMatch: ending!.version })}
      >
        <DateField name="effectiveTo" label={t('hr.effectiveTo')} required />
      </FormDialog>
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t('hr.deleteAssignment')}
        destructive
        confirmLabel={t('common.delete')}
        onConfirm={() => api.delete('/employees/{employeeId}/assignments/{assignmentId}', { employeeId, assignmentId: deleting!.id! }, { ifMatch: deleting!.version })}
      />
    </Section>
  );
}

// --- Compensation (Payroll) -------------------------------------------------------------------

const compensationSchema = z.object({
  payScheduleId: zf.id(),
  salaryStructureId: zf.id(),
  baseAmount: zf.decimal(),
  effectiveFrom: zf.date(),
  effectiveTo: zf.optionalDate(),
});

function CompensationSection({ employee: e }: { employee: Employee }) {
  const { api, can } = useCompany();
  const employeeId = e.id!;
  const query = useCompanyQuery(['employees', employeeId, 'compensations'], (c, signal) => c.get('/employees/{employeeId}/compensations', { employeeId }, { signal }));
  const [adding, setAdding] = useState(false);
  const [ending, setEnding] = useState<Schemas['Compensation'] | null>(null);
  const [deleting, setDeleting] = useState<Schemas['Compensation'] | null>(null);
  const manage = can('payroll.compensation.manage');
  const rows = query.data?.data ?? [];
  return (
    <Section title={t('hr.compensation')} actions={manage ? <Button size="sm" onClick={() => setAdding(true)}><Plus aria-hidden />{t('hr.newCompensation')}</Button> : null} bodyClassName="p-0">
      {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState /> : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('hr.effectiveFrom')}</TableHead>
                <TableHead>{t('hr.effectiveTo')}</TableHead>
                <TableHead className="text-right">{t('hr.baseAmount')}</TableHead>
                <TableHead>{t('hr.structure')}</TableHead>
                <TableHead className="hidden md:table-cell">{t('hr.schedule')}</TableHead>
                <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((c) => (
                <TableRow key={c.id}>
                  <TableCell><DateText value={c.effectiveFrom} /></TableCell>
                  <TableCell><DateText value={c.effectiveTo} /></TableCell>
                  <TableCell className="text-right"><Money value={c.baseAmount} currency={c.currencyCode} /></TableCell>
                  <TableCell><EntityName source={entities.salaryStructure} id={c.salaryStructureId} /></TableCell>
                  <TableCell className="hidden md:table-cell"><EntityName source={entities.paySchedule} id={c.payScheduleId} /></TableCell>
                  <TableCell className="text-right">
                    {manage && !c.effectiveTo ? <Button size="sm" variant="ghost" onClick={() => setEnding(c)}>{t('hr.endCompensation')}</Button> : null}
                    {manage && (c.effectiveFrom ?? '') > todayIso() ? (
                      <Button size="icon-sm" variant="ghost" onClick={() => setDeleting(c)} aria-label={t('common.delete')}><Trash2 aria-hidden /></Button>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <FormDialog
        open={adding}
        onOpenChange={setAdding}
        title={t('hr.newCompensation')}
        schema={compensationSchema}
        defaults={{ payScheduleId: null, salaryStructureId: null, baseAmount: null, effectiveFrom: todayIso(), effectiveTo: null }}
        success={t('common.saved')}
        onSubmit={(v) => api.post('/employees/{employeeId}/compensations', { employeeId }, { body: compact(v) as Schemas['CompensationRequest'] })}
      >
        <EntityField name="payScheduleId" label={t('hr.schedule')} source={entities.paySchedule} required />
        <EntityField name="salaryStructureId" label={t('hr.structure')} source={entities.salaryStructure} required />
        <DecimalField name="baseAmount" label={t('hr.baseAmount')} required displayScale={2} />
        <FieldGrid>
          <DateField name="effectiveFrom" label={t('hr.effectiveFrom')} required />
          <DateField name="effectiveTo" label={t('hr.effectiveTo')} />
        </FieldGrid>
      </FormDialog>
      <FormDialog
        open={ending !== null}
        onOpenChange={(open) => !open && setEnding(null)}
        title={t('hr.endCompensation')}
        schema={endSchema}
        defaults={{ effectiveTo: todayIso() }}
        success={t('common.saved')}
        onSubmit={(v) => api.patch('/employees/{employeeId}/compensations/{compensationId}', { employeeId, compensationId: ending!.id! }, { body: { effectiveTo: v.effectiveTo }, ifMatch: ending!.version })}
      >
        <DateField name="effectiveTo" label={t('hr.effectiveTo')} required />
      </FormDialog>
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t('common.delete')}
        destructive
        confirmLabel={t('common.delete')}
        onConfirm={() => api.delete('/employees/{employeeId}/compensations/{compensationId}', { employeeId, compensationId: deleting!.id! }, { ifMatch: deleting!.version })}
      />
    </Section>
  );
}

// --- Bank accounts and documents --------------------------------------------------------------

const employeeBankSchema = z.object({
  bankName: zf.text(100),
  accountHolder: zf.optionalText(200),
  accountNumber: zf.optionalText(40),
  iban: zf.optionalText(40),
  swiftBic: zf.optionalText(11),
});

function EmployeeBankSection({ employee: e }: { employee: Employee }) {
  const { api } = useCompany();
  const employeeId = e.id!;
  const query = useCompanyQuery(['employees', employeeId, 'bank-accounts'], (c, signal) => c.get('/employees/{employeeId}/bank-accounts', { employeeId }, { signal }));
  const [adding, setAdding] = useState(false);
  const [revealed, setRevealed] = useState<Record<string, Schemas['EmployeeRecordsRevealedBankAccount']>>({});
  const [removing, setRemoving] = useState<Schemas['BankAccountResponse'] | null>(null);
  const rows = query.data?.data ?? [];
  const run = async (work: () => Promise<unknown>) => {
    try {
      await work();
      await query.refetch();
    } catch (error) {
      notify.error(error);
    }
  };
  return (
    <Section title={t('hr.bankAccounts')} actions={<Button size="sm" onClick={() => setAdding(true)}><Plus aria-hidden />{t('org.newBankAccount')}</Button>}>
      {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState /> : (
        <ul className="divide-y">
          {rows.map((b) => (
            <li key={b.id} className="flex flex-wrap items-center gap-2 py-2 text-sm">
              <div className="min-w-0 flex-1">
                <div className="font-medium">{b.bankName} {b.primary ? <StatusBadge status="ACTIVE" label={t('fields.primary')} /> : null}</div>
                <div className="font-mono text-xs text-muted-foreground">
                  {revealed[b.id!] ? [revealed[b.id!]!.accountNumber, revealed[b.id!]!.iban].filter(Boolean).join(' · ') : b.accountNumberMasked}
                </div>
              </div>
              {!revealed[b.id!] ? (
                <Button size="sm" variant="ghost" onClick={() => void api.post('/employees/{employeeId}/bank-accounts/{accountId}/reveal', { employeeId, accountId: b.id! }).then((r) => setRevealed((x) => ({ ...x, [b.id!]: r })), notify.error)}>
                  <Eye aria-hidden />{t('common.reveal')}
                </Button>
              ) : null}
              {!b.primary ? (
                <Button size="sm" variant="ghost" onClick={() => void run(() => api.post('/employees/{employeeId}/bank-accounts/{accountId}/make-primary', { employeeId, accountId: b.id! }))}>
                  {t('hr.makePrimary')}
                </Button>
              ) : null}
              <Button size="icon-sm" variant="ghost" onClick={() => setRemoving(b)} aria-label={`${t('common.remove')} ${b.bankName}`}><Trash2 aria-hidden /></Button>
            </li>
          ))}
        </ul>
      )}
      <FormDialog
        open={adding}
        onOpenChange={setAdding}
        title={t('org.newBankAccount')}
        schema={employeeBankSchema}
        defaults={{ bankName: '', accountHolder: employeeName(e), accountNumber: '', iban: '', swiftBic: '' }}
        success={t('common.saved')}
        onSubmit={(v) => api.post('/employees/{employeeId}/bank-accounts', { employeeId }, { body: compact(v) as Schemas['EmployeeRecordsBankAccountRequest'] })}
      >
        <TextField name="bankName" label={t('fields.bankName')} required />
        <TextField name="accountHolder" label={t('fields.accountHolder')} />
        <FieldGrid>
          <TextField name="accountNumber" label={t('fields.accountNumber')} />
          <TextField name="iban" label={t('fields.iban')} />
          <TextField name="swiftBic" label={t('fields.swiftBic')} />
        </FieldGrid>
      </FormDialog>
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('org.removeConfirm', { name: removing?.bankName ?? '' })}
        destructive
        confirmLabel={t('common.remove')}
        onConfirm={async () => {
          await api.delete('/employees/{employeeId}/bank-accounts/{accountId}', { employeeId, accountId: removing!.id! });
          await query.refetch();
        }}
      />
    </Section>
  );
}

const DOCUMENT_TYPES = ['CONTRACT', 'IDENTITY', 'CERTIFICATE', 'WORK_PERMIT', 'REVIEW', 'OTHER'];
const documentSchema = z.object({ documentType: zf.id(), title: zf.text(200), validUntil: zf.optionalDate() });

/** Employee documents: the attachments panel over the HR documents API (downloads are audited). */
function DocumentsSection({ employee: e }: { employee: Employee }) {
  const { api } = useCompany();
  const employeeId = e.id!;
  const query = useCompanyQuery(['employees', employeeId, 'documents'], (c, signal) => c.get('/employees/{employeeId}/documents', { employeeId }, { signal }));
  return (
    <AttachmentsPanel
      title={t('hr.documents')}
      items={(query.data?.data ?? []).map((d) => ({
        id: d.id!,
        name: d.fileName ?? d.title ?? '',
        contentType: d.contentType,
        size: d.sizeBytes,
        createdAt: d.createdAt,
        description: (
          <>
            {enumLabel(d.documentType)} · {d.title}
            {d.validUntil ? <> · {t('hr.validUntil')}: <DateText value={d.validUntil} /></> : null}
          </>
        ),
      }))}
      isLoading={query.isLoading}
      error={query.error}
      onRetry={() => query.refetch()}
      canUpload
      canDelete
      uploadForm={(file, done) => (
        <UploadDocumentForm
          file={file}
          onCancel={done}
          onUpload={async (v) => {
            const body = new FormData();
            body.append('file', file);
            await api.post('/employees/{employeeId}/documents', { employeeId }, {
              body,
              query: { documentType: v.documentType!, title: v.title, validUntil: v.validUntil ?? undefined },
            });
            await query.refetch();
            notify.success(t('attachments.uploaded'));
            done();
          }}
        />
      )}
      onDownload={(item) => download(api.url('/employees/{employeeId}/documents/{documentId}/content', { employeeId, documentId: item.id }), item.name)}
      onDelete={async (item) => {
        await api.delete('/employees/{employeeId}/documents/{documentId}', { employeeId, documentId: item.id });
        await query.refetch();
      }}
    />
  );
}

function UploadDocumentForm({
  file,
  onCancel,
  onUpload,
}: {
  file: File;
  onCancel: () => void;
  onUpload: (values: z.infer<typeof documentSchema>) => Promise<void>;
}) {
  const form = useForm<z.infer<typeof documentSchema>>({
    resolver: zodResolver(documentSchema),
    defaultValues: { documentType: 'OTHER', title: file.name.replace(/\.[^.]+$/, ''), validUntil: null },
  });
  const { submit, ...problem } = useSubmit(form, onUpload);
  return (
    <Form form={form} onSubmit={submit}>
      <p className="text-sm font-medium">{file.name}</p>
      <FieldGrid columns={3}>
        <SelectField name="documentType" label={t('hr.documentType')} options={enumOptions(DOCUMENT_TYPES)} required />
        <TextField name="title" label={t('hr.documentTitle')} required />
        <DateField name="validUntil" label={t('hr.validUntil')} />
      </FieldGrid>
      <FormProblem {...problem} />
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>{t('attachments.upload')}</Button>
        <Button type="button" size="sm" variant="outline" onClick={onCancel}>{t('common.cancel')}</Button>
      </div>
    </Form>
  );
}

