import { createFileRoute } from '@tanstack/react-router';
import { SalesReturnsPage } from '@/modules/sales/deliveries';

export const Route = createFileRoute('/_authed/c/$companyId/sales/returns/')({ component: SalesReturnsPage });
