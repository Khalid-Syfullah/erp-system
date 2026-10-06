import { createFileRoute } from '@tanstack/react-router';
import { BankAccountPage } from '@/modules/accounting/bank';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/bank-accounts/$bankAccountId')({ component: BankAccountPage });
