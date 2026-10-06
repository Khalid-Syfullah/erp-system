import { createFileRoute } from '@tanstack/react-router';
import { MyPayslipsPage } from '@/modules/payroll/runs';

export const Route = createFileRoute('/_authed/c/$companyId/me/payslips/')({ component: MyPayslipsPage });
