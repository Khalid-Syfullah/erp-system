import { createFileRoute } from '@tanstack/react-router';
import { PurchaseReturnPage } from '@/modules/procurement/receipts';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/returns/$returnId')({ component: PurchaseReturnPage });
