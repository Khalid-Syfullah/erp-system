import { createFileRoute, Link } from '@tanstack/react-router';
import { useState } from 'react';
import { api } from '@/api/client';
import { AuthLayout } from '@/auth/auth-layout';
import { useFragmentToken } from '@/auth/fragment-token';
import { NewPasswordForm } from '@/auth/new-password-form';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { t } from '@/i18n';

export const Route = createFileRoute('/reset-password')({
  component: ResetPasswordPage,
});

function ResetPasswordPage() {
  const token = useFragmentToken();
  const [done, setDone] = useState(false);
  return (
    <AuthLayout title={t('auth.resetTitle')}>
      {done ? (
        <Alert>
          <AlertDescription>{t('auth.resetDone')}</AlertDescription>
        </Alert>
      ) : (
        <NewPasswordForm
          submitLabel={t('common.save')}
          onSubmit={async (newPassword) => {
            await api.post('/api/v1/auth/password/reset', {}, { body: { token, newPassword }, anonymous: true });
            setDone(true);
          }}
        />
      )}
      <p className="text-center text-sm">
        <Link to="/login" className="text-primary underline-offset-4 hover:underline">
          {t('auth.backToSignIn')}
        </Link>
      </p>
    </AuthLayout>
  );
}
