import { createFileRoute } from '@tanstack/react-router';
import { QuotationPage } from '@/modules/sales/orders';

export const Route = createFileRoute('/_authed/c/$companyId/sales/quotations/$quotationId')({ component: QuotationPage });
