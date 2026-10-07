import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api } from '@/api/client';
import { LanguageSwitcher } from '@/auth/language';
import { RecoveryCodes, MfaEnrollment } from '@/auth/mfa-enrollment';
import { companiesOf, meQuery, useMe } from '@/auth/session';
import { PageHeader, Section } from '@/components/common/page';
import { DateTimeText } from '@/components/common/values';
import { StatusBadge } from '@/components/common/status-badge';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { Form, FieldGrid, IntegerField, SelectField, TextField } from '@/components/form/fields';
import { zf, mergePatch } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { t } from '@/i18n';

export function AccountPage() {
  return (
    <div className="max-w-4xl space-y-4">
      <PageHeader title={t('account.title')} />
      <Tabs defaultValue="profile">
        <TabsList className="flex-wrap">
          <TabsTrigger value="profile">{t('account.profile')}</TabsTrigger>
          <TabsTrigger value="security">{t('account.mfa')}</TabsTrigger>
          <TabsTrigger value="sessions">{t('account.sessions')}</TabsTrigger>
          <TabsTrigger value="tokens">{t('account.tokens')}</TabsTrigger>
        </TabsList>
        <TabsContent value="profile" className="space-y-4">
          <ProfileSection />
          <PasswordSection />
        </TabsContent>
        <TabsContent value="security">
          <MfaSection />
        </TabsContent>
        <TabsContent value="sessions">
          <SessionsSection />
        </TabsContent>
        <TabsContent value="tokens">
          <TokensSection />
        </TabsContent>
      </Tabs>
    </div>
  );
}

const profileSchema = z.object({
  displayName: zf.text(100),
  timezone: zf.optionalText(64),
});

function ProfileSection() {
  const me = useMe();
  const queryClient = useQueryClient();
  const initial = { displayName: me.user?.displayName ?? '', timezone: me.user?.timezone ?? '' };
  const form = useForm<z.infer<typeof profileSchema>>({ resolver: zodResolver(profileSchema), defaultValues: initial });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.patch('/api/v1/me', {}, { body: mergePatch(initial, values), ifMatch: me.user?.version });
    await queryClient.invalidateQueries({ queryKey: meQuery.queryKey });
    notify.success(t('common.saved'));
  });
  return (
    <Section title={t('account.profile')}>
      <Form form={form} onSubmit={submit}>
        <TextField name="displayName" label={t('account.displayName')} required maxLength={100} />
        <div className="space-y-1.5">
          <div className="text-sm font-medium">{t('account.locale')}</div>
          <LanguageSwitcher />
          <p className="text-xs text-muted-foreground">{t('account.localeHint')}</p>
        </div>
        <FieldGrid>
          <TextField name="timezone" label={t('account.timezone')} hint={t('account.timezoneHint')} />
        </FieldGrid>
        <p className="text-sm text-muted-foreground">{me.user?.email}</p>
        <FormProblem {...problem} />
        <Button type="submit" disabled={form.formState.isSubmitting}>
          {t('common.save')}
        </Button>
      </Form>
    </Section>
  );
}

const passwordSchema = z
  .object({ currentPassword: z.string().min(1, t('forms.required')), newPassword: z.string().min(1, t('forms.required')), confirm: z.string() })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: t('auth.passwordsDiffer') });

function PasswordSection() {
  const form = useForm<z.infer<typeof passwordSchema>>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirm: '' },
  });
  const { submit, ...problem } = useSubmit(form, async ({ currentPassword, newPassword }) => {
    await api.post('/api/v1/me/password', {}, { body: { currentPassword, newPassword } });
    form.reset();
    notify.success(t('account.passwordChanged'));
  });
  return (
    <Section title={t('account.changePassword')}>
      <Form form={form} onSubmit={submit}>
        <TextField name="currentPassword" label={t('auth.currentPassword')} type="password" autoComplete="current-password" required />
        <FieldGrid>
          <TextField name="newPassword" label={t('auth.newPassword')} type="password" autoComplete="new-password" hint={t('auth.passwordRules')} required />
          <TextField name="confirm" label={t('auth.confirmPassword')} type="password" autoComplete="new-password" required />
        </FieldGrid>
        <FormProblem {...problem} />
        <Button type="submit" disabled={form.formState.isSubmitting}>
          {t('account.changePassword')}
        </Button>
      </Form>
    </Section>
  );
}

function MfaSection() {
  const me = useMe();
  const queryClient = useQueryClient();
  const [enrolling, setEnrolling] = useState(false);
  const [confirm, setConfirm] = useState<'disable' | 'regenerate' | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: meQuery.queryKey });
  const enabled = !!me.user?.mfaEnabled;
  return (
    <Section title={t('account.mfa')}>
      <div className="space-y-3">
        <p className="text-sm">{enabled ? t('account.mfaOn') : t('account.mfaOff')}</p>
        <div className="flex flex-wrap gap-2">
          {enabled ? (
            <>
              <Button variant="outline" onClick={() => setConfirm('regenerate')}>
                {t('account.mfaRegenerate')}
              </Button>
              {!me.mfaRequired ? (
                <Button variant="destructive" onClick={() => setConfirm('disable')}>
                  {t('account.mfaDisable')}
                </Button>
              ) : null}
            </>
          ) : (
            <Button onClick={() => setEnrolling(true)}>{t('account.mfaEnable')}</Button>
          )}
        </div>
      </div>
      <Modal open={enrolling} onOpenChange={setEnrolling} title={t('auth.enrollTitle')} size="sm">
        {enrolling ? (
          <MfaEnrollment
            onDone={() => {
              setEnrolling(false);
              void refresh();
            }}
          />
        ) : null}
      </Modal>
      <Modal open={codes !== null} onOpenChange={(open) => !open && setCodes(null)} title={t('auth.recoveryCodesTitle')} size="sm">
        {codes ? <RecoveryCodes codes={codes} onDone={() => setCodes(null)} /> : null}
      </Modal>
      <ConfirmDialog
        open={confirm === 'disable'}
        onOpenChange={(open) => !open && setConfirm(null)}
        title={t('account.mfaDisableConfirm')}
        destructive
        confirmLabel={t('account.mfaDisable')}
        onConfirm={async () => {
          await api.delete('/api/v1/me/mfa/totp', {});
          await refresh();
        }}
      />
      <ConfirmDialog
        open={confirm === 'regenerate'}
        onOpenChange={(open) => !open && setConfirm(null)}
        title={t('account.mfaRegenerateConfirm')}
        confirmLabel={t('account.mfaRegenerate')}
        onConfirm={async () => {
          const result = await api.post('/api/v1/me/mfa/recovery-codes', {});
          setCodes(result.recoveryCodes ?? []);
        }}
      />
    </Section>
  );
}

function SessionsSection() {
  const query = useQuery({ queryKey: ['me', 'sessions'], queryFn: ({ signal }) => api.get('/api/v1/me/sessions', {}, { signal }) });
  const [revoking, setRevoking] = useState<string | null>(null);
  return (
    <Section title={t('account.sessions')} bodyClassName="p-0">
      {query.isLoading ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => query.refetch()} />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('account.device')}</TableHead>
                <TableHead>{t('account.lastSeen')}</TableHead>
                <TableHead>{t('account.expires')}</TableHead>
                <TableHead>
                  <span className="sr-only">{t('common.actions')}</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(query.data?.data ?? []).map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="max-w-72">
                    <div className="truncate" title={s.userAgent ?? ''}>
                      {s.userAgent ?? '—'}
                    </div>
                    <div className="text-xs text-muted-foreground">{s.ip}</div>
                    {s.current ? <StatusBadge status="ACTIVE" label={t('account.currentSession')} /> : null}
                  </TableCell>
                  <TableCell>
                    <DateTimeText value={s.lastSeenAt} />
                  </TableCell>
                  <TableCell>
                    <DateTimeText value={s.absoluteExpiresAt} />
                  </TableCell>
                  <TableCell className="text-right">
                    {!s.current ? (
                      <Button variant="ghost" size="sm" onClick={() => setRevoking(s.id!)}>
                        {t('account.revoke')}
                      </Button>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(open) => !open && setRevoking(null)}
        title={t('account.revokeConfirm')}
        destructive
        confirmLabel={t('account.revoke')}
        onConfirm={async () => {
          await api.delete('/api/v1/me/sessions/{sessionId}', { sessionId: revoking! });
          await query.refetch();
        }}
      />
    </Section>
  );
}

const tokenSchema = z.object({
  name: zf.text(100),
  expiresInDays: zf.optionalInteger(),
  companyId: z.string().nullable(),
  allowedPermissions: zf.optionalText(4000),
  rateLimitPerMinute: zf.optionalInteger(),
});

function TokensSection() {
  const me = useMe();
  const query = useQuery({ queryKey: ['me', 'api-tokens'], queryFn: ({ signal }) => api.get('/api/v1/me/api-tokens', {}, { signal }) });
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);
  const form = useForm<z.infer<typeof tokenSchema>>({
    resolver: zodResolver(tokenSchema),
    defaultValues: { name: '', expiresInDays: 90, companyId: null, allowedPermissions: '', rateLimitPerMinute: null },
  });
  const create = useMutation({
    mutationFn: (values: z.infer<typeof tokenSchema>) =>
      api.post('/api/v1/me/api-tokens', {}, {
        body: {
          name: values.name,
          expiresInDays: values.expiresInDays ?? undefined,
          companyId: values.companyId ?? undefined,
          rateLimitPerMinute: values.rateLimitPerMinute ?? undefined,
          allowedPermissions: values.allowedPermissions
            ? values.allowedPermissions.split(',').map((p) => p.trim()).filter(Boolean)
            : undefined,
        },
      }),
  });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    const result = await create.mutateAsync(values);
    setSecret(result.token ?? null);
    setCreating(false);
    form.reset();
    await query.refetch();
  });
  const companies = companiesOf(me);
  return (
    <Section
      title={t('account.tokens')}
      actions={
        <Button size="sm" onClick={() => setCreating(true)}>
          {t('account.tokenCreate')}
        </Button>
      }
      bodyClassName="p-0"
    >
      {query.isLoading ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => query.refetch()} />
      ) : (query.data?.data ?? []).length === 0 ? (
        <EmptyState />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('account.tokenName')}</TableHead>
                <TableHead>{t('account.tokenPrefix')}</TableHead>
                <TableHead>{t('account.lastUsed')}</TableHead>
                <TableHead>{t('account.expires')}</TableHead>
                <TableHead>
                  <span className="sr-only">{t('common.actions')}</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(query.data?.data ?? []).map((token) => (
                <TableRow key={token.id}>
                  <TableCell>
                    {token.name} {token.revokedAt ? <StatusBadge status="REVOKED" label={t('account.revoked')} tone="danger" /> : null}
                  </TableCell>
                  <TableCell className="font-mono text-xs">{token.prefix}</TableCell>
                  <TableCell>
                    <DateTimeText value={token.lastUsedAt} />
                  </TableCell>
                  <TableCell>
                    <DateTimeText value={token.expiresAt} />
                  </TableCell>
                  <TableCell className="text-right">
                    {!token.revokedAt ? (
                      <Button variant="ghost" size="sm" onClick={() => setRevoking(token.id!)}>
                        {t('account.tokenRevoke')}
                      </Button>
                    ) : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <Modal open={creating} onOpenChange={setCreating} title={t('account.tokenCreate')}>
        <Form form={form} onSubmit={submit}>
          <TextField name="name" label={t('account.tokenName')} required />
          <FieldGrid>
            <IntegerField name="expiresInDays" label={t('account.tokenExpiresIn')} min={1} />
            <IntegerField name="rateLimitPerMinute" label={t('account.tokenRate')} min={1} />
          </FieldGrid>
          <SelectField
            name="companyId"
            label={t('account.tokenCompany')}
            allowEmpty
            options={companies.map((c) => ({ value: c.id!, label: c.displayName ?? c.code ?? '' }))}
          />
          <TextField name="allowedPermissions" label={t('account.tokenPermissions')} hint={t('account.tokenPermissionsHint')} />
          <FormProblem {...problem} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => setCreating(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {t('account.tokenCreate')}
            </Button>
          </div>
        </Form>
      </Modal>
      <Modal open={secret !== null} onOpenChange={(open) => !open && setSecret(null)} title={t('account.tokenCreate')} size="md">
        <p className="text-sm">{t('account.tokenCreated')}</p>
        <code className="block rounded bg-muted p-2 font-mono text-xs break-all">{secret}</code>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => void navigator.clipboard?.writeText(secret ?? '')}>
            {t('common.copy')}
          </Button>
          <Button onClick={() => setSecret(null)}>{t('common.close')}</Button>
        </div>
      </Modal>
      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(open) => !open && setRevoking(null)}
        title={t('account.tokenRevokeConfirm')}
        destructive
        confirmLabel={t('account.tokenRevoke')}
        onConfirm={async () => {
          await api.delete('/api/v1/me/api-tokens/{tokenId}', { tokenId: revoking! });
          await query.refetch();
        }}
      />
    </Section>
  );
}
