import { createFileRoute } from '@tanstack/react-router';
import { StockLevelsPage } from '@/modules/inventory/stock';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/stock')({ component: StockLevelsPage });
