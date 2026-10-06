import { createFileRoute } from '@tanstack/react-router';
import { SalesSettingsPage } from '@/modules/sales/settings';

export const Route = createFileRoute('/_authed/c/$companyId/sales/settings')({ component: SalesSettingsPage });
