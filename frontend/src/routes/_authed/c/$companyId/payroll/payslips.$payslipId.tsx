import { createFileRoute } from '@tanstack/react-router';
import { PayslipPage } from '@/modules/payroll/runs';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/payslips/$payslipId')({ component: PayslipPage });
