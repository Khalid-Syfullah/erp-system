import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi } from '@tanstack/react-router';
import { Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, type Schemas } from '@/api/client';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { BooleanBadge, StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText } from '@/components/common/values';
import { DocumentActions, type DocAction } from '@/components/document/actions';
import { ErrorState, LoadingState, EmptyState } from '@/components/feedback/states';
import { CheckboxField, DateField, FieldGrid, Form, SelectField, TextField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { notify } from '@/components/feedback/notify';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { serverText, t } from '@/i18n';
import { useGlobalQuery, useInvalidateGlobal } from './use-global';

type User = Schemas['UserResponse'];

const userAction = (
  id: string,
  label: string,
  path: '/api/v1/admin/users/{userId}/disable' | '/api/v1/admin/users/{userId}/enable' | '/api/v1/admin/users/{userId}/unlock' | '/api/v1/admin/users/{userId}/reset-mfa' | '/api/v1/admin/users/{userId}/resend-invite',
  when: (u: User) => boolean,
  confirm?: string,
): DocAction<User> => ({
  id,
  label,
  when,
  confirm: confirm ? { title: confirm, destructive: id === 'disable' } : undefined,
  variant: id === 'disable' ? 'destructive' : undefined,
  run: (_, u) => api.post(path, { userId: u.id! }, { ifMatch: u.version }),
  success: t('common.saved'),
});

const actions: DocAction<User>[] = [
  userAction('disable', t('admin.disable'), '/api/v1/admin/users/{userId}/disable', (u) => u.status === 'ACTIVE' || u.status === 'LOCKED' || u.status === 'INVITED', t('admin.disableConfirm')),
  userAction('enable', t('admin.enable'), '/api/v1/admin/users/{userId}/enable', (u) => u.status === 'DISABLED'),
  userAction('unlock', t('admin.unlock'), '/api/v1/admin/users/{userId}/unlock', (u) => u.status === 'LOCKED'),
  userAction('reset-mfa', t('admin.resetMfa'), '/api/v1/admin/users/{userId}/reset-mfa', (u) => !!u.mfaEnabled, t('admin.resetMfaConfirm')),
  userAction('resend', t('admin.resendInvite'), '/api/v1/admin/users/{userId}/resend-invite', (u) => u.status === 'INVITED'),
  {
    id: 'revoke-sessions',
    label: t('admin.revokeSessions'),
    when: (u) => u.status === 'ACTIVE',
    confirm: { title: t('admin.revokeSessionsConfirm') },
    run: (_, u) => api.post('/api/v1/admin/users/{userId}/revoke-sessions', { userId: u.id! }),
    success: t('common.saved'),
  },
];

const route = getRouteApi('/_authed/admin/users/$userId');

export function AdminUserPage() {
  const { userId } = route.useParams();
  const user = useGlobalQuery(['user', userId], (signal) => api.get('/api/v1/admin/users/{userId}', { userId }, { signal }));
  const [editing, setEditing] = useState(false);
  if (user.isLoading) return <LoadingState />;
  if (user.isError || !user.data) return <ErrorState error={user.error} onRetry={() => user.refetch()} />;
  const u = user.data;
  return (
    <div className="space-y-4">
      <PageHeader
        title={u.displayName ?? u.email}
        description={u.email}
        badge={<StatusBadge status={u.status} />}
        actions={
          <>
            <Button variant="outline" onClick={() => setEditing(true)}>
              {t('common.edit')}
            </Button>
            <DocumentActions doc={u} actions={actions} />
          </>
        }
      />
      <Section>
        <DetailList
          items={[
            { label: t('admin.userType'), value: u.userType },
            { label: t('admin.systemAdmin'), value: <BooleanBadge value={u.isSystemAdmin} yes={t('common.yes')} no={t('common.no')} /> },
            { label: t('admin.mfa'), value: <BooleanBadge value={u.mfaEnabled} yes={t('common.yes')} no={t('common.no')} /> },
            { label: t('account.locale'), value: u.locale ?? '—' },
            { label: t('account.timezone'), value: u.timezone ?? '—' },
            { label: t('admin.lastLogin'), value: <DateTimeText value={u.lastLoginAt} /> },
          ]}
        />
      </Section>
      <AssignmentsSection userId={userId} />
      {editing ? <EditUserModal user={u} onClose={() => setEditing(false)} /> : null}
    </div>
  );
}

const editSchema = z.object({
  displayName: zf.text(100),
  locale: zf.optionalText(35),
  timezone: zf.optionalText(64),
  isSystemAdmin: z.boolean(),
});

function EditUserModal({ user, onClose }: { user: User; onClose: () => void }) {
  const invalidate = useInvalidateGlobal();
  const initial = { displayName: user.displayName ?? '', locale: user.locale ?? '', timezone: user.timezone ?? '', isSystemAdmin: !!user.isSystemAdmin };
  const form = useForm<z.infer<typeof editSchema>>({ resolver: zodResolver(editSchema), defaultValues: initial });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.patch('/api/v1/admin/users/{userId}', { userId: user.id! }, { body: mergePatch(initial, values), ifMatch: user.version });
    await invalidate();
    notify.success(t('common.saved'));
    onClose();
  });
  return (
    <Modal open onOpenChange={(open) => !open && onClose()} title={t('admin.editUser')}>
      <Form form={form} onSubmit={submit}>
        <TextField name="displayName" label={t('admin.displayName')} required />
        <FieldGrid>
          <TextField name="locale" label={t('account.locale')} />
          <TextField name="timezone" label={t('account.timezone')} />
        </FieldGrid>
        <CheckboxField name="isSystemAdmin" label={t('admin.systemAdmin')} />
        <FormProblem {...problem} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {t('common.save')}
          </Button>
        </div>
      </Form>
    </Modal>
  );
}

const assignSchema = z.object({ companyId: zf.id(), roleId: zf.id(), validFrom: zf.optionalDate(), validTo: zf.optionalDate() });

function AssignmentsSection({ userId }: { userId: string }) {
  const invalidate = useInvalidateGlobal();
  const assignments = useGlobalQuery(['user', userId, 'assignments'], (signal) =>
    api.get('/api/v1/admin/users/{userId}/role-assignments', { userId }, { signal }),
  );
  const companies = useGlobalQuery(['companies-all'], (signal) => api.get('/api/v1/admin/companies', {}, { query: { limit: 200 }, signal }));
  const roles = useGlobalQuery(['roles'], (signal) => api.get('/api/v1/admin/roles', {}, { signal }));
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const companyName = (id?: string) => companies.data?.data?.find((c) => c.id === id)?.displayName ?? id;
  const form = useForm<z.infer<typeof assignSchema>>({
    resolver: zodResolver(assignSchema),
    defaultValues: { companyId: null, roleId: null, validFrom: null, validTo: null },
  });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.post('/api/v1/admin/users/{userId}/role-assignments', { userId }, {
      body: { companyId: values.companyId!, roleId: values.roleId!, validFrom: values.validFrom ?? undefined, validTo: values.validTo ?? undefined },
    });
    await invalidate();
    form.reset();
    setAdding(false);
  });
  const rows: Schemas['AuthAssignmentResponse'][] = assignments.data?.data ?? [];
  return (
    <Section
      title={t('admin.assignments')}
      actions={
        <Button size="sm" onClick={() => setAdding(true)}>
          <Plus aria-hidden />
          {t('admin.assign')}
        </Button>
      }
      bodyClassName="p-0"
    >
      {assignments.isLoading ? (
        <LoadingState />
      ) : assignments.isError ? (
        <ErrorState error={assignments.error} />
      ) : rows.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('admin.company')}</TableHead>
                <TableHead>{t('admin.role')}</TableHead>
                <TableHead>{t('admin.branches')}</TableHead>
                <TableHead>{t('admin.validFrom')}</TableHead>
                <TableHead>{t('admin.validTo')}</TableHead>
                <TableHead>
                  <span className="sr-only">{t('common.actions')}</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((a) => (
                <TableRow key={a.id}>
                  <TableCell>{companyName(a.companyId)}</TableCell>
                  <TableCell>{a.roleCode}</TableCell>
                  <TableCell>{a.branchIds && a.branchIds.length > 0 ? a.branchIds.length : t('admin.allBranches')}</TableCell>
                  <TableCell>
                    <DateText value={a.validFrom} />
                  </TableCell>
                  <TableCell>
                    <DateText value={a.validTo} />
                  </TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="icon-sm" onClick={() => setRemoving(a.id!)} aria-label={t('admin.removeAssignment')}>
                      <Trash2 aria-hidden />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <Modal open={adding} onOpenChange={setAdding} title={t('admin.assign')}>
        <Form form={form} onSubmit={submit}>
          <SelectField
            name="companyId"
            label={t('admin.company')}
            required
            options={(companies.data?.data ?? []).map((c) => ({ value: c.id!, label: `${c.code} — ${c.displayName}` }))}
          />
          <SelectField
            name="roleId"
            label={t('admin.role')}
            required
            options={(roles.data?.data ?? []).map((r) => ({ value: r.id!, label: `${serverText(r.name)} (${r.code})` }))}
          />
          <FieldGrid>
            <DateField name="validFrom" label={t('admin.validFrom')} />
            <DateField name="validTo" label={t('admin.validTo')} />
          </FieldGrid>
          <FormProblem {...problem} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setAdding(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {t('admin.assign')}
            </Button>
          </div>
        </Form>
      </Modal>
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('admin.removeAssignmentConfirm')}
        destructive
        confirmLabel={t('admin.removeAssignment')}
        onConfirm={async () => {
          await api.delete('/api/v1/admin/users/{userId}/role-assignments/{assignmentId}', { userId, assignmentId: removing! });
          await invalidate();
        }}
      />
    </Section>
  );
}
