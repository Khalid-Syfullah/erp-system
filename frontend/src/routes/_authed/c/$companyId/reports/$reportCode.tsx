import { createFileRoute } from '@tanstack/react-router';
import { ReportRunPage } from '@/modules/reports/report-run-page';

// Report parameters live in the URL (shareable, back/forward), validated by the server.
export const Route = createFileRoute('/_authed/c/$companyId/reports/$reportCode')({
  validateSearch: (search: Record<string, unknown>) => search as Record<string, string | undefined>,
  component: ReportRunPage,
});
