import { createFileRoute } from '@tanstack/react-router';
import { LeaveTypesPage } from '@/modules/hr/hr-master';

export const Route = createFileRoute('/_authed/c/$companyId/hr/leave-types')({ component: LeaveTypesPage });
