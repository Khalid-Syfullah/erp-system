import { createFileRoute } from '@tanstack/react-router';
import { DeliveriesPage } from '@/modules/sales/deliveries';

export const Route = createFileRoute('/_authed/c/$companyId/sales/deliveries/')({ component: DeliveriesPage });
