import { createFileRoute } from '@tanstack/react-router';
import { ProcurementSettingsPage } from '@/modules/procurement/settings';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/settings')({ component: ProcurementSettingsPage });
