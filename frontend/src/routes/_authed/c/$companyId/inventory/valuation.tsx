import { createFileRoute } from '@tanstack/react-router';
import { ValuationPage } from '@/modules/inventory/stock';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/valuation')({ component: ValuationPage });
