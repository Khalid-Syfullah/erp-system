import { createFileRoute } from '@tanstack/react-router';
import { SalesOrdersPage } from '@/modules/sales/orders';

export const Route = createFileRoute('/_authed/c/$companyId/sales/orders/')({ component: SalesOrdersPage });
