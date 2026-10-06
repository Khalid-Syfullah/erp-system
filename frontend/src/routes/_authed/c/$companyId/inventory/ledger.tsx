import { createFileRoute } from '@tanstack/react-router';
import { StockLedgerPage } from '@/modules/inventory/stock';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/ledger')({ component: StockLedgerPage });
