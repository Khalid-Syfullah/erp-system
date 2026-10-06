import { createFileRoute } from '@tanstack/react-router';
import { GoodsReceiptsPage } from '@/modules/procurement/receipts';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/receipts/')({ component: GoodsReceiptsPage });
