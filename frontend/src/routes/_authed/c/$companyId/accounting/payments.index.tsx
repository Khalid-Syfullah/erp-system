import { createFileRoute } from '@tanstack/react-router';
import { PaymentsPage } from '@/modules/accounting/payments';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/payments/')({ component: PaymentsPage });
