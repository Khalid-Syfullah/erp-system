import { createFileRoute } from '@tanstack/react-router';
import { PaySchedulesPage } from '@/modules/payroll/setup';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/schedules')({ component: PaySchedulesPage });
