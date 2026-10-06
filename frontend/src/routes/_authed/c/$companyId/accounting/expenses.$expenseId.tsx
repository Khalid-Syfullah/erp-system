import { createFileRoute } from '@tanstack/react-router';
import { ExpensePage } from '@/modules/accounting/expenses';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/expenses/$expenseId')({ component: ExpensePage });
