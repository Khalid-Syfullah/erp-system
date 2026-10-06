import { createFileRoute } from '@tanstack/react-router';
import { InventorySettingsPage } from '@/modules/inventory/settings';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/settings')({ component: InventorySettingsPage });
