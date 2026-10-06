import { useQueryClient } from '@tanstack/react-query';
import { createFileRoute, redirect, useNavigate } from '@tanstack/react-router';
import { isApiError } from '@/api/errors';
import { AuthLayout } from '@/auth/auth-layout';
import { MfaEnrollment } from '@/auth/mfa-enrollment';
import { meQuery } from '@/auth/session';
import { t } from '@/i18n';

/** Forced TOTP enrollment for users whose roles require MFA (SECURITY.md §3.5). */
export const Route = createFileRoute('/mfa-setup')({
  beforeLoad: async ({ context, location }) => {
    try {
      await context.queryClient.ensureQueryData(meQuery);
    } catch (error) {
      if (isApiError(error) && error.status === 401) throw redirect({ to: '/login', search: { redirect: location.href } });
      throw error;
    }
  },
  component: MfaSetupPage,
});

function MfaSetupPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  return (
    <AuthLayout title={t('auth.enrollTitle')}>
      <MfaEnrollment
        onDone={async () => {
          queryClient.removeQueries();
          await queryClient.fetchQuery(meQuery);
          await navigate({ to: '/' });
        }}
      />
    </AuthLayout>
  );
}
