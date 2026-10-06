import { createFileRoute } from '@tanstack/react-router';
import { QuotationsPage } from '@/modules/sales/orders';

export const Route = createFileRoute('/_authed/c/$companyId/sales/quotations/')({ component: QuotationsPage });
