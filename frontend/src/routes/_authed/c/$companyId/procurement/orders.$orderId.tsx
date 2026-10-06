import { createFileRoute } from '@tanstack/react-router';
import { PurchaseOrderPage } from '@/modules/procurement/purchase-orders';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/orders/$orderId')({ component: PurchaseOrderPage });
