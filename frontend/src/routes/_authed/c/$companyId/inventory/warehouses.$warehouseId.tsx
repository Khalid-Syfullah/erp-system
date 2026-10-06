import { createFileRoute } from '@tanstack/react-router';
import { WarehousePage } from '@/modules/inventory/warehouses';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/warehouses/$warehouseId')({ component: WarehousePage });
