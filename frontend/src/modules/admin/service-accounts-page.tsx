import { zodResolver } from '@hookform/resolvers/zod';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, type Schemas } from '@/api/client';
import { PageHeader } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateTimeText } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { Form, IntegerField, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Drawer } from '@/components/overlay/drawer';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { useGlobalQuery, useInvalidateGlobal } from './use-global';

type User = Schemas['UserResponse'];

const accountSchema = z.object({ email: zf.email(), displayName: zf.text(100) });
const tokenSchema = z.object({ name: zf.text(100), expiresInDays: zf.optionalInteger(), allowedPermissions: zf.optionalText(4000) });

export function AdminServiceAccountsPage() {
  const [creating, setCreating] = useState(false);
  const [selected, setSelected] = useState<User | null>(null);
  const invalidate = useInvalidateGlobal();
  const form = useForm<z.infer<typeof accountSchema>>({ resolver: zodResolver(accountSchema), defaultValues: { email: '', displayName: '' } });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.post('/api/v1/admin/service-accounts', {}, { body: values });
    await invalidate();
    form.reset();
    setCreating(false);
  });
  return (
    <div className="space-y-4">
      <PageHeader title={t('admin.serviceAccounts')} />
      <DataTable<User>
        id="admin-service-accounts"
        fetchPage={(_, query, signal) => api.get('/api/v1/admin/service-accounts', {}, { query, signal })}
        rowKey={(u) => u.id!}
        onRowOpen={setSelected}
        columns={[
          {
            id: 'email',
            header: t('admin.email'),
            sortKey: 'email',
            cell: (u) => (
              <Button variant="link" className="h-auto p-0" data-row-link onClick={() => setSelected(u)}>
                {u.email}
              </Button>
            ),
          },
          { id: 'name', header: t('admin.displayName'), cell: (u) => u.displayName },
          { id: 'status', header: t('common.status'), cell: (u) => <StatusBadge status={u.status} /> },
        ]}
        toolbar={
          <Button onClick={() => setCreating(true)}>
            <Plus aria-hidden />
            {t('admin.newServiceAccount')}
          </Button>
        }
      />
      <Modal open={creating} onOpenChange={setCreating} title={t('admin.newServiceAccount')}>
        <Form form={form} onSubmit={submit}>
          <TextField name="email" label={t('admin.email')} type="email" required />
          <TextField name="displayName" label={t('admin.displayName')} required />
          <FormProblem {...problem} />
          <div className="flex justify-end">
            <Button type="submit">{t('common.create')}</Button>
          </div>
        </Form>
      </Modal>
      <Drawer open={selected !== null} onOpenChange={(open) => !open && setSelected(null)} title={selected?.displayName ?? ''} wide>
        {selected ? <ServiceTokens account={selected} /> : null}
      </Drawer>
    </div>
  );
}

function ServiceTokens({ account }: { account: User }) {
  const userId = account.id!;
  const tokens = useGlobalQuery(['service-account', userId, 'tokens'], (signal) =>
    api.get('/api/v1/admin/service-accounts/{userId}/api-tokens', { userId }, { signal }),
  );
  const invalidate = useInvalidateGlobal();
  const [secret, setSecret] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);
  const form = useForm<z.infer<typeof tokenSchema>>({ resolver: zodResolver(tokenSchema), defaultValues: { name: '', expiresInDays: 90, allowedPermissions: '' } });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    const result = await api.post('/api/v1/admin/service-accounts/{userId}/api-tokens', { userId }, {
      body: {
        name: values.name,
        expiresInDays: values.expiresInDays ?? undefined,
        allowedPermissions: values.allowedPermissions ? values.allowedPermissions.split(',').map((p) => p.trim()).filter(Boolean) : undefined,
      },
    });
    setSecret(result.token ?? null);
    form.reset();
    await invalidate();
  });
  return (
    <div className="space-y-4">
      <h3 className="font-semibold">{t('admin.tokens')}</h3>
      {tokens.isLoading ? (
        <LoadingState />
      ) : tokens.isError ? (
        <ErrorState error={tokens.error} />
      ) : (tokens.data?.data ?? []).length === 0 ? (
        <EmptyState />
      ) : (
        <ul className="divide-y rounded-lg border">
          {(tokens.data?.data ?? []).map((token) => (
            <li key={token.id} className="flex items-center gap-2 p-2 text-sm">
              <div className="flex-1">
                <div className="font-medium">{token.name}</div>
                <div className="text-xs text-muted-foreground">
                  {token.prefix} · <DateTimeText value={token.expiresAt} />
                </div>
              </div>
              {token.revokedAt ? (
                <StatusBadge status="REVOKED" tone="danger" label={t('account.revoked')} />
              ) : (
                <Button size="sm" variant="ghost" onClick={() => setRevoking(token.id!)}>
                  {t('account.tokenRevoke')}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      <Form form={form} onSubmit={submit} className="space-y-3 rounded-lg border p-3">
        <h4 className="text-sm font-semibold">{t('admin.newToken')}</h4>
        <TextField name="name" label={t('account.tokenName')} required />
        <IntegerField name="expiresInDays" label={t('account.tokenExpiresIn')} min={1} />
        <TextField name="allowedPermissions" label={t('account.tokenPermissions')} hint={t('account.tokenPermissionsHint')} />
        <FormProblem {...problem} />
        <Button type="submit">{t('account.tokenCreate')}</Button>
      </Form>
      {secret ? (
        <div className="space-y-2 rounded-lg border border-status-warning-fg/40 bg-status-warning-bg p-3 text-sm">
          <p>{t('account.tokenCreated')}</p>
          <code className="block font-mono text-xs break-all">{secret}</code>
        </div>
      ) : null}
      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(open) => !open && setRevoking(null)}
        title={t('account.tokenRevokeConfirm')}
        destructive
        confirmLabel={t('account.tokenRevoke')}
        onConfirm={async () => {
          await api.delete('/api/v1/admin/service-accounts/{userId}/api-tokens/{tokenId}', { userId, tokenId: revoking! });
          await invalidate();
        }}
      />
    </div>
  );
}
