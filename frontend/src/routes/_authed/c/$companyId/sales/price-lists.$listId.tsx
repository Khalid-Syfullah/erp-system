import { createFileRoute } from '@tanstack/react-router';
import { PriceListPage } from '@/modules/sales/price-lists';

export const Route = createFileRoute('/_authed/c/$companyId/sales/price-lists/$listId')({ component: PriceListPage });
