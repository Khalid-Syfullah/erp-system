import { createFileRoute } from '@tanstack/react-router';
import { PayrollRunPage } from '@/modules/payroll/runs';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/runs/$runId')({ component: PayrollRunPage });
