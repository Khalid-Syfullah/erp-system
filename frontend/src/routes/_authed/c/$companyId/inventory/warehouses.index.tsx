import { createFileRoute } from '@tanstack/react-router';
import { WarehousesPage } from '@/modules/inventory/warehouses';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/warehouses/')({ component: WarehousesPage });
