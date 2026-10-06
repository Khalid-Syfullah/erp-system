import { createFileRoute } from '@tanstack/react-router';
import { AccountsPage } from '@/modules/accounting/setup';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/accounts')({ component: AccountsPage });
