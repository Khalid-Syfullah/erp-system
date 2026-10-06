import { createFileRoute } from '@tanstack/react-router';
import { ReportCentrePage } from '@/modules/reports/report-centre';

export const Route = createFileRoute('/_authed/c/$companyId/reports/')({ component: ReportCentrePage });
