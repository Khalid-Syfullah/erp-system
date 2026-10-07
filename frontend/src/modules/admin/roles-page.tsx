import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Plus } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, type Schemas } from '@/api/client';
import { PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, Form, TextField, TextareaField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { searchable, serverText, t } from '@/i18n';
import { useGlobalQuery, useInvalidateGlobal } from './use-global';

type Role = Schemas['RoleResponse'];

const roleSchema = z.object({
  code: zf.text(50),
  name: zf.text(100),
  description: zf.optionalText(500),
  requiresMfa: z.boolean(),
});

export function AdminRolesPage() {
  const roles = useGlobalQuery(['roles'], (signal) => api.get('/api/v1/admin/roles', {}, { signal }));
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();
  const invalidate = useInvalidateGlobal();
  const form = useForm<z.infer<typeof roleSchema>>({
    resolver: zodResolver(roleSchema),
    defaultValues: { code: '', name: '', description: '', requiresMfa: false },
  });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    const role = await api.post('/api/v1/admin/roles', {}, { body: { ...values, permissions: [] } });
    await invalidate();
    setCreating(false);
    await navigate({ to: '/admin/roles/$roleId', params: { roleId: role.id! } });
  });
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('admin.roles')}
        actions={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            {t('admin.newRole')}
          </Button>
        }
      />
      <Section bodyClassName="p-0">
        {roles.isLoading ? (
          <LoadingState />
        ) : roles.isError ? (
          <ErrorState error={roles.error} onRetry={() => roles.refetch()} />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('common.code')}</TableHead>
                  <TableHead>{t('common.name')}</TableHead>
                  <TableHead>{t('common.type')}</TableHead>
                  <TableHead className="text-right">{t('admin.permissions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(roles.data?.data ?? []).map((role) => (
                  <TableRow key={role.id}>
                    <TableCell>
                      <Link to="/admin/roles/$roleId" params={{ roleId: role.id! }} className="font-mono text-xs text-primary hover:underline">
                        {role.code}
                      </Link>
                    </TableCell>
                    <TableCell>{serverText(role.name)}</TableCell>
                    <TableCell>
                      <StatusBadge status={role.isSystem ? 'ACTIVE' : 'OPEN'} label={role.isSystem ? t('admin.systemRole') : t('admin.customRole')} />
                    </TableCell>
                    <TableCell className="text-right tabular">{role.permissions?.length ?? 0}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
      <Modal open={creating} onOpenChange={setCreating} title={t('admin.newRole')}>
        <Form form={form} onSubmit={submit}>
          <TextField name="code" label={t('common.code')} required />
          <TextField name="name" label={t('common.name')} required />
          <TextareaField name="description" label={t('common.description')} />
          <CheckboxField name="requiresMfa" label={t('admin.requiresMfa')} />
          <FormProblem {...problem} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setCreating(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit">{t('common.create')}</Button>
          </div>
        </Form>
      </Modal>
    </div>
  );
}

const roleRoute = getRouteApi('/_authed/admin/roles/$roleId');

export function AdminRolePage() {
  const { roleId } = roleRoute.useParams();
  const role = useGlobalQuery(['role', roleId], (signal) => api.get('/api/v1/admin/roles/{roleId}', { roleId }, { signal }));
  const catalog = useGlobalQuery(['permissions'], (signal) => api.get('/api/v1/admin/permissions', {}, { signal }));
  if (role.isLoading || catalog.isLoading) return <LoadingState />;
  if (role.isError || !role.data) return <ErrorState error={role.error} onRetry={() => role.refetch()} />;
  if (catalog.isError) return <ErrorState error={catalog.error} />;
  // A new version (after saving) starts the editor again from the server's permission set.
  return <RoleEditor key={role.data.version} role={role.data} catalog={catalog.data?.data ?? []} />;
}

function RoleEditor({ role, catalog }: { role: Role; catalog: Schemas['PermissionResponse'][] }) {
  const invalidate = useInvalidateGlobal();
  const navigate = useNavigate();
  const [selected, setSelected] = useState<Set<string>>(() => new Set(role.permissions ?? []));
  const [filter, setFilter] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [deleting, setDeleting] = useState(false);
  const readOnly = !!role.isSystem;
  const modules = useMemo(() => {
    const groups = new Map<string, Schemas['PermissionResponse'][]>();
    for (const p of catalog) {
      if (filter && !searchable(`${p.code} ${serverText(p.description)} ${p.description}`).includes(searchable(filter))) continue;
      const list = groups.get(p.module ?? '') ?? [];
      list.push(p);
      groups.set(p.module ?? '', list);
    }
    return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [catalog, filter]);

  const initial = { name: role.name ?? '', description: role.description ?? '', requiresMfa: !!role.requiresMfa };
  const form = useForm({ defaultValues: initial });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    const patch = mergePatch(initial, values);
    if (Object.keys(patch).length > 0) {
      await api.patch('/api/v1/admin/roles/{roleId}', { roleId: role.id! }, { body: patch, ifMatch: role.version });
    }
    await invalidate();
    notify.success(t('common.saved'));
  });

  const savePermissions = async () => {
    setError(null);
    try {
      await api.put('/api/v1/admin/roles/{roleId}/permissions', { roleId: role.id! }, { body: { permissions: [...selected].sort() }, ifMatch: role.version });
      await invalidate();
      notify.success(t('common.saved'));
    } catch (e) {
      setError(e);
    }
  };

  return (
    <div className="space-y-4">
      <PageHeader
        title={serverText(role.name)}
        description={<span className="font-mono">{role.code}</span>}
        badge={<StatusBadge status={role.isSystem ? 'ACTIVE' : 'OPEN'} label={role.isSystem ? t('admin.systemRole') : t('admin.customRole')} />}
        actions={
          !readOnly ? (
            <Button variant="destructive" onClick={() => setDeleting(true)}>
              {t('admin.deleteRole')}
            </Button>
          ) : null
        }
      />
      {!readOnly ? (
        <Section title={t('common.details')}>
          <Form form={form} onSubmit={submit}>
            <TextField name="name" label={t('common.name')} required />
            <TextareaField name="description" label={t('common.description')} />
            <CheckboxField name="requiresMfa" label={t('admin.requiresMfa')} />
            <FormProblem {...problem} />
            <Button type="submit">{t('common.save')}</Button>
          </Form>
        </Section>
      ) : null}
      <Section
        title={`${t('admin.permissions')} (${selected.size})`}
        actions={
          <>
            <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t('common.searchPlaceholder')} aria-label={t('common.search')} className="w-48" />
            {!readOnly ? <Button size="sm" onClick={() => void savePermissions()}>{t('admin.savePermissions')}</Button> : null}
          </>
        }
      >
        {error ? <FormProblem error={error} unmapped={[]} /> : null}
        <div className="space-y-4">
          {modules.map(([module, permissions]) => (
            <fieldset key={module} className="space-y-1.5">
              <legend className="mb-1 text-sm font-semibold capitalize">{module}</legend>
              {permissions.map((p) => {
                const id = `perm-${p.code}`;
                return (
                  <div key={p.code} className="flex items-start gap-2">
                    <Checkbox
                      id={id}
                      checked={selected.has(p.code!)}
                      disabled={readOnly}
                      onCheckedChange={(checked) =>
                        setSelected((current) => {
                          const next = new Set(current);
                          if (checked === true) next.add(p.code!);
                          else next.delete(p.code!);
                          return next;
                        })
                      }
                    />
                    <label htmlFor={id} className="text-sm">
                      <span className="font-mono text-xs">{p.code}</span>
                      {p.isSensitive ? <StatusBadge status="WARNING" label={t('admin.sensitive')} className="ml-2" /> : null}
                      <span className="block text-xs text-muted-foreground">{serverText(p.description)}</span>
                    </label>
                  </div>
                );
              })}
            </fieldset>
          ))}
        </div>
      </Section>
      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={t('admin.deleteRoleConfirm')}
        destructive
        confirmLabel={t('admin.deleteRole')}
        onConfirm={async () => {
          await api.delete('/api/v1/admin/roles/{roleId}', { roleId: role.id! }, { ifMatch: role.version });
          await invalidate();
          await navigate({ to: '/admin/roles' });
        }}
      />
    </div>
  );
}
