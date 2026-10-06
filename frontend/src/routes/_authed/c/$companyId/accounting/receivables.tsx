import { createFileRoute } from '@tanstack/react-router';
import { ReceivablesPage } from '@/modules/accounting/open-items';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/receivables')({ component: ReceivablesPage });
