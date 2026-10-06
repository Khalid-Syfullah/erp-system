import { createFileRoute } from '@tanstack/react-router';
import { NewSalesOrderPage } from '@/modules/sales/orders';

export const Route = createFileRoute('/_authed/c/$companyId/sales/orders/new')({ component: NewSalesOrderPage });
