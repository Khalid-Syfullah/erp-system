import { createFileRoute } from '@tanstack/react-router';
import { HrSettingsPage } from '@/modules/hr/hr-master';

export const Route = createFileRoute('/_authed/c/$companyId/hr/settings')({ component: HrSettingsPage });
