import { createFileRoute } from '@tanstack/react-router';
import { PayrollPeriodPage } from '@/modules/payroll/runs';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/periods/$periodId')({ component: PayrollPeriodPage });
