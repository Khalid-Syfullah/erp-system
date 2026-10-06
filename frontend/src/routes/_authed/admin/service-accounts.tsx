import { createFileRoute } from '@tanstack/react-router';
import { AdminServiceAccountsPage } from '@/modules/admin/service-accounts-page';

export const Route = createFileRoute('/_authed/admin/service-accounts')({ component: AdminServiceAccountsPage });
