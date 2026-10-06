import { createFileRoute } from '@tanstack/react-router';
import { ExpensesPage } from '@/modules/accounting/expenses';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/expenses/')({ component: ExpensesPage });
