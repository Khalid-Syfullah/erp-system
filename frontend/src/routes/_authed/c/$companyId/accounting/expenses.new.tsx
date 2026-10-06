import { createFileRoute } from '@tanstack/react-router';
import { NewExpensePage } from '@/modules/accounting/expenses';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/expenses/new')({ component: NewExpensePage });
