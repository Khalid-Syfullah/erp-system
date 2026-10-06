import { zodResolver } from '@hookform/resolvers/zod';
import { createFileRoute, Link } from '@tanstack/react-router';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api } from '@/api/client';
import { AuthLayout } from '@/auth/auth-layout';
import { Form, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';

export const Route = createFileRoute('/forgot-password')({ component: ForgotPasswordPage });

const schema = z.object({ email: zf.email() });

function ForgotPasswordPage() {
  const [sent, setSent] = useState(false);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { email: '' } });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.post('/api/v1/auth/password/forgot', {}, { body: values, anonymous: true });
    setSent(true);
  });
  return (
    <AuthLayout title={t('auth.forgotTitle')} description={sent ? undefined : t('auth.forgotText')}>
      {sent ? (
        <Alert>
          <AlertDescription>{t('auth.forgotSent')}</AlertDescription>
        </Alert>
      ) : (
        <Form form={form} onSubmit={submit}>
          <TextField name="email" label={t('auth.email')} type="email" autoComplete="email" required />
          <FormProblem {...problem} />
          <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
            {t('auth.sendLink')}
          </Button>
        </Form>
      )}
      <p className="text-center text-sm">
        <Link to="/login" className="text-primary underline-offset-4 hover:underline">
          {t('auth.backToSignIn')}
        </Link>
      </p>
    </AuthLayout>
  );
}
