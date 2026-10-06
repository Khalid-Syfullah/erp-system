import { createFileRoute } from '@tanstack/react-router';
import { EmployeePage } from '@/modules/hr/employees';

export const Route = createFileRoute('/_authed/c/$companyId/hr/employees/$employeeId')({ component: EmployeePage });
