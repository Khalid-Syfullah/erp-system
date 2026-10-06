import { createFileRoute } from '@tanstack/react-router';
import { GoodsReceiptPage } from '@/modules/procurement/receipts';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/receipts/$receiptId')({ component: GoodsReceiptPage });
