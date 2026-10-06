import { createFileRoute } from '@tanstack/react-router';
import { PaymentTermsPage } from '@/modules/org/master-pages';

export const Route = createFileRoute('/_authed/c/$companyId/org/payment-terms')({ component: PaymentTermsPage });
