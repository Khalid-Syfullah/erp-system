import { createFileRoute } from '@tanstack/react-router';
import { SavedReportsPage } from '@/modules/reports/report-centre';

export const Route = createFileRoute('/_authed/c/$companyId/reports/saved')({ component: SavedReportsPage });
