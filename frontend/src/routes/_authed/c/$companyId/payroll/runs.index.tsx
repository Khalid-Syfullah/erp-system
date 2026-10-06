import { createFileRoute } from '@tanstack/react-router';
import { PayrollRunsPage } from '@/modules/payroll/runs';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/runs/')({ component: PayrollRunsPage });
