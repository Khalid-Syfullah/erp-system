import { createFileRoute } from '@tanstack/react-router';
import { BankAccountsPage } from '@/modules/accounting/bank';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/bank-accounts/')({ component: BankAccountsPage });
