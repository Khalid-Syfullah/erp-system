import { createFileRoute } from '@tanstack/react-router';
import { DepartmentsPage } from '@/modules/org/master-pages';

export const Route = createFileRoute('/_authed/c/$companyId/org/departments')({ component: DepartmentsPage });
