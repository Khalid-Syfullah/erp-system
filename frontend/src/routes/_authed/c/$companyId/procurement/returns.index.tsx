import { createFileRoute } from '@tanstack/react-router';
import { PurchaseReturnsPage } from '@/modules/procurement/receipts';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/returns/')({ component: PurchaseReturnsPage });
