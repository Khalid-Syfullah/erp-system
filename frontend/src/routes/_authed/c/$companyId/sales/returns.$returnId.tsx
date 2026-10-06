import { createFileRoute } from '@tanstack/react-router';
import { SalesReturnPage } from '@/modules/sales/deliveries';

export const Route = createFileRoute('/_authed/c/$companyId/sales/returns/$returnId')({ component: SalesReturnPage });
