import { createFileRoute } from '@tanstack/react-router';
import { SalesOrderPage } from '@/modules/sales/orders';

export const Route = createFileRoute('/_authed/c/$companyId/sales/orders/$orderId')({ component: SalesOrderPage });
