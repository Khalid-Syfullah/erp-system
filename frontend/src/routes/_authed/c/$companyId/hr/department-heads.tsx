import { createFileRoute } from '@tanstack/react-router';
import { DepartmentHeadsPage } from '@/modules/hr/hr-master';

export const Route = createFileRoute('/_authed/c/$companyId/hr/department-heads')({ component: DepartmentHeadsPage });
