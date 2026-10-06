import { createFileRoute } from '@tanstack/react-router';
import { PriceListsPage } from '@/modules/sales/price-lists';

export const Route = createFileRoute('/_authed/c/$companyId/sales/price-lists/')({ component: PriceListsPage });
