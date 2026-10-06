import { createFileRoute, notFound, Outlet } from '@tanstack/react-router';
import { meQuery } from '@/auth/session';

/** System administration (ADR-032): system administrators only; the API enforces it again. */
export const Route = createFileRoute('/_authed/admin')({
  beforeLoad: async ({ context }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    if (!me.user?.isSystemAdmin) throw notFound();
  },
  component: Outlet,
});
