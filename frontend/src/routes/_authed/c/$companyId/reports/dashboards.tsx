import { createFileRoute } from '@tanstack/react-router';
import { DashboardsPage } from '@/modules/reports/report-centre';

export const Route = createFileRoute('/_authed/c/$companyId/reports/dashboards')({ component: DashboardsPage });
