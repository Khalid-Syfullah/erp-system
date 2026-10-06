import { createFileRoute } from '@tanstack/react-router';
import { LeaveRequestsPage } from '@/modules/hr/leave';

export const Route = createFileRoute('/_authed/c/$companyId/hr/leave-requests')({ component: LeaveRequestsPage });
