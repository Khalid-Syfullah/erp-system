import { createFileRoute } from '@tanstack/react-router';
import { SalaryStructuresPage } from '@/modules/payroll/setup';

export const Route = createFileRoute('/_authed/c/$companyId/payroll/structures')({ component: SalaryStructuresPage });
