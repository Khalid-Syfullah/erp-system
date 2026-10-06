import { useQueryClient } from '@tanstack/react-query';
import { createFileRoute, Outlet, redirect, useNavigate, useRouter } from '@tanstack/react-router';
import { useEffect } from 'react';
import { setAuthHandlers } from '@/api/client';
import { isApiError } from '@/api/errors';
import { meQuery } from '@/auth/session';
import { AppShell } from '@/layout/app-shell';

/** The signed-in area: requires a session, and an enrolled second factor where MFA is mandatory. */
export const Route = createFileRoute('/_authed')({
  beforeLoad: async ({ context, location }) => {
    let me;
    try {
      me = await context.queryClient.ensureQueryData(meQuery);
    } catch (error) {
      if (isApiError(error) && error.status === 401) {
        throw redirect({ to: '/login', search: { redirect: location.href } });
      }
      throw error;
    }
    if (me.mfaEnrollmentRequired) throw redirect({ to: '/mfa-setup' });
    return { me };
  },
  component: AuthedLayout,
});

function AuthedLayout() {
  const navigate = useNavigate();
  const router = useRouter();
  const queryClient = useQueryClient();

  useEffect(
    () =>
      setAuthHandlers({
        onUnauthenticated: () => {
          const current = router.state.location.href;
          queryClient.clear();
          void navigate({ to: '/login', search: { redirect: current, expired: true } });
        },
        onMfaEnrollmentRequired: () => void navigate({ to: '/mfa-setup' }),
      }),
    [navigate, queryClient, router],
  );

  return (
    <AppShell>
      <Outlet />
    </AppShell>
  );
}
