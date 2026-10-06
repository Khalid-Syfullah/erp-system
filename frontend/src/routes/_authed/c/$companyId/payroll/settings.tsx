import { createFileRoute } from '@tanstack/react-router';
import { PayrollSettingsPage } from '@/modules/payroll/setup';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/settings')({ component: PayrollSettingsPage });
