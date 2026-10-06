import { createFileRoute } from '@tanstack/react-router';
import { PayrollPeriodsPage } from '@/modules/payroll/runs';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/periods/')({ component: PayrollPeriodsPage });
