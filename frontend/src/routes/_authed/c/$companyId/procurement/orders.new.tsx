import { createFileRoute } from '@tanstack/react-router';
import { NewPurchaseOrderPage } from '@/modules/procurement/purchase-orders';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/orders/new')({ component: NewPurchaseOrderPage });
