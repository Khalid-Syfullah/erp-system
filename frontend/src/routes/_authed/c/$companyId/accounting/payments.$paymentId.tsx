import { createFileRoute } from '@tanstack/react-router';
import { PaymentPage } from '@/modules/accounting/payments';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/payments/$paymentId')({ component: PaymentPage });
