import { zodResolver } from '@hookform/resolvers/zod';
import { Link } from '@tanstack/react-router';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, type Schemas } from '@/api/client';
import { PageHeader } from '@/components/common/page';
import { BooleanBadge, StatusBadge } from '@/components/common/status-badge';
import { DateTimeText } from '@/components/common/values';
import { DataTable, type Column } from '@/components/data/data-table';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, Form, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { useInvalidateGlobal } from './use-global';

type User = Schemas['UserResponse'];

export function userColumns(): Column<User>[] {
  return [
    {
      id: 'email',
      header: t('admin.email'),
      sortKey: 'email',
      cell: (u) => (
        <Link to="/admin/users/$userId" params={{ userId: u.id! }} data-row-link className="font-medium text-primary hover:underline">
          {u.email}
        </Link>
      ),
    },
    { id: 'name', header: t('admin.displayName'), sortKey: 'displayName', cell: (u) => u.displayName },
    { id: 'status', header: t('admin.status'), cell: (u) => <StatusBadge status={u.status} /> },
    { id: 'admin', header: t('admin.systemAdmin'), hideBelow: 'md', cell: (u) => (u.isSystemAdmin ? <BooleanBadge value yes={t('common.yes')} no={t('common.no')} /> : null) },
    { id: 'mfa', header: t('admin.mfa'), hideBelow: 'md', cell: (u) => <BooleanBadge value={u.mfaEnabled} yes={t('common.yes')} no={t('common.no')} /> },
    { id: 'login', header: t('admin.lastLogin'), hideBelow: 'lg', cell: (u) => <DateTimeText value={u.lastLoginAt} /> },
  ];
}

const inviteSchema = z.object({ email: zf.email(), displayName: zf.text(100), isSystemAdmin: z.boolean() });

export function AdminUsersPage() {
  const [inviting, setInviting] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('admin.users')} />
      <DataTable
        id="admin-users"
        fetchPage={(_, query, signal) => api.get('/api/v1/admin/users', {}, { query, signal })}
        columns={userColumns()}
        rowKey={(u) => u.id!}
        defaultSort="email"
        filters={[
          { kind: 'enum', key: 'status', label: t('admin.status'), values: ['INVITED', 'ACTIVE', 'LOCKED', 'DISABLED'] },
          { kind: 'boolean', key: 'isSystemAdmin', label: t('admin.systemAdmin') },
        ]}
        fixedFilters={{ userType: 'HUMAN' }}
        toolbar={
          <Button onClick={() => setInviting(true)}>
            <Plus aria-hidden />
            {t('admin.invite')}
          </Button>
        }
      />
      <InviteUserModal open={inviting} onOpenChange={setInviting} />
    </div>
  );
}

function InviteUserModal({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const invalidate = useInvalidateGlobal();
  const form = useForm<z.infer<typeof inviteSchema>>({
    resolver: zodResolver(inviteSchema),
    defaultValues: { email: '', displayName: '', isSystemAdmin: false },
  });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.post('/api/v1/admin/users', {}, { body: values });
    await invalidate();
    notify.success(t('admin.invited'));
    form.reset();
    onOpenChange(false);
  });
  return (
    <Modal open={open} onOpenChange={onOpenChange} title={t('admin.invite')} description={t('admin.inviteText')}>
      <Form form={form} onSubmit={submit}>
        <TextField name="email" label={t('admin.email')} type="email" required />
        <TextField name="displayName" label={t('admin.displayName')} required />
        <CheckboxField name="isSystemAdmin" label={t('admin.systemAdmin')} />
        <FormProblem {...problem} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {t('admin.invite')}
          </Button>
        </div>
      </Form>
    </Modal>
  );
}
