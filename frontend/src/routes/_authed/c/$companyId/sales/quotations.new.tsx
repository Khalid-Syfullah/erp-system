import { createFileRoute } from '@tanstack/react-router';
import { NewQuotationPage } from '@/modules/sales/orders';

export const Route = createFileRoute('/_authed/c/$companyId/sales/quotations/new')({ component: NewQuotationPage });
