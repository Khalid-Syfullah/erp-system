import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { createFileRoute, Link, useNavigate } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, ensureCsrfToken } from '@/api/client';
import { hasCode } from '@/api/errors';
import { AuthLayout } from '@/auth/auth-layout';
import { safeRedirect } from '@/auth/redirect';
import { meQuery } from '@/auth/session';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Form, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { t } from '@/i18n';

interface LoginSearch {
  redirect?: string;
  expired?: boolean;
}

export const Route = createFileRoute('/login')({
  validateSearch: (search: Record<string, unknown>): LoginSearch => ({
    redirect: typeof search.redirect === 'string' ? search.redirect : undefined,
    expired: search.expired === true || search.expired === 'true' ? true : undefined,
  }),
  component: LoginPage,
});

interface LoginResult {
  mfaEnrollmentRequired?: boolean;
}

function LoginPage() {
  const search = Route.useSearch();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [step, setStep] = useState<'password' | 'mfa'>('password');

  useEffect(() => {
    void ensureCsrfToken();
  }, []);

  const finish = async (result: LoginResult | undefined) => {
    queryClient.removeQueries();
    await queryClient.fetchQuery(meQuery);
    if (result?.mfaEnrollmentRequired) {
      await navigate({ to: '/mfa-setup' });
    } else {
      await navigate({ to: safeRedirect(search.redirect) as '/' });
    }
  };

  return (
    <AuthLayout title={step === 'password' ? t('auth.signIn') : t('auth.mfaTitle')} description={step === 'mfa' ? t('auth.mfaText') : undefined}>
      {search.expired && step === 'password' ? (
        <Alert>
          <AlertDescription>{t('auth.sessionExpired')}</AlertDescription>
        </Alert>
      ) : null}
      {step === 'password' ? (
        <PasswordStep onDone={finish} onMfa={() => setStep('mfa')} />
      ) : (
        <MfaStep onDone={finish} onBack={() => setStep('password')} />
      )}
    </AuthLayout>
  );
}

const passwordSchema = z.object({ email: zf.email(), password: z.string().min(1, t('forms.required')) });

function PasswordStep({ onDone, onMfa }: { onDone: (r: LoginResult | undefined) => Promise<void>; onMfa: () => void }) {
  const form = useForm<z.infer<typeof passwordSchema>>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { email: '', password: '' },
  });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    try {
      const result = (await api.post('/api/v1/auth/login', {}, { body: values, anonymous: true })) as LoginResult;
      await onDone(result);
    } catch (error) {
      if (hasCode(error, 'MFA_REQUIRED')) {
        onMfa();
        return;
      }
      throw error;
    }
  });
  return (
    <Form form={form} onSubmit={submit}>
      <TextField name="email" label={t('auth.email')} type="email" autoComplete="username" required />
      <TextField name="password" label={t('auth.password')} type="password" autoComplete="current-password" required />
      <FormProblem {...problem} />
      <Button type="submit" className="w-full" size="lg" disabled={form.formState.isSubmitting}>
        {form.formState.isSubmitting ? t('auth.signingIn') : t('auth.signIn')}
      </Button>
      <div className="text-center text-sm">
        <Link to="/forgot-password" className="text-primary underline-offset-4 hover:underline">
          {t('auth.forgotPassword')}
        </Link>
      </div>
    </Form>
  );
}

const mfaSchema = z.object({ code: z.string().trim().min(1, t('forms.required')) });

function MfaStep({ onDone, onBack }: { onDone: (r: LoginResult | undefined) => Promise<void>; onBack: () => void }) {
  const [recovery, setRecovery] = useState(false);
  const form = useForm<z.infer<typeof mfaSchema>>({ resolver: zodResolver(mfaSchema), defaultValues: { code: '' } });
  const { submit, ...problem } = useSubmit(form, async ({ code }) => {
    const body = recovery ? { recoveryCode: code } : { code: code.replace(/\s/g, '') };
    const result = (await api.post('/api/v1/auth/login/mfa', {}, { body, anonymous: true })) as LoginResult;
    await onDone(result);
  });
  return (
    <Form form={form} onSubmit={submit}>
      <TextField
        name="code"
        label={recovery ? t('auth.recoveryCode') : t('auth.mfaCode')}
        autoComplete="one-time-code"
        required
        maxLength={recovery ? 20 : 8}
      />
      <FormProblem {...problem} />
      <Button type="submit" className="w-full" size="lg" disabled={form.formState.isSubmitting}>
        {t('auth.verify')}
      </Button>
      <div className="flex justify-between text-sm">
        <button type="button" className="text-primary underline-offset-4 hover:underline" onClick={() => setRecovery((r) => !r)}>
          {recovery ? t('auth.useCode') : t('auth.useRecovery')}
        </button>
        <button type="button" className="text-muted-foreground underline-offset-4 hover:underline" onClick={onBack}>
          {t('auth.backToSignIn')}
        </button>
      </div>
    </Form>
  );
}
