import { createFileRoute } from '@tanstack/react-router';
import { ExportsPage } from '@/modules/reports/report-centre';

export const Route = createFileRoute('/_authed/c/$companyId/reports/exports')({ component: ExportsPage });
