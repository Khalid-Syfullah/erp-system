import { createFileRoute } from '@tanstack/react-router';
import { MyPayslipPage } from '@/modules/payroll/runs';

export const Route = createFileRoute('/_authed/c/$companyId/me/payslips/$payslipId')({ component: MyPayslipPage });
