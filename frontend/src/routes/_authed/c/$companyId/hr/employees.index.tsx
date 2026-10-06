import { createFileRoute } from '@tanstack/react-router';
import { EmployeesPage } from '@/modules/hr/employees';

export const Route = createFileRoute('/_authed/c/$companyId/hr/employees/')({ component: EmployeesPage });
