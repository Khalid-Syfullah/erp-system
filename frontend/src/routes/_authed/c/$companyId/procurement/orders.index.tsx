import { createFileRoute } from '@tanstack/react-router';
import { PurchaseOrdersPage } from '@/modules/procurement/purchase-orders';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/orders/')({ component: PurchaseOrdersPage });
