import { createFileRoute } from '@tanstack/react-router';
import { PayComponentsPage } from '@/modules/payroll/setup';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/components')({ component: PayComponentsPage });
