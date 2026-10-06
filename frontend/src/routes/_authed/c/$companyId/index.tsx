import { createFileRoute } from '@tanstack/react-router';
import { DashboardPage } from '@/modules/dashboard/dashboard-page';

export const Route = createFileRoute('/_authed/c/$companyId/')({ component: DashboardPage });
