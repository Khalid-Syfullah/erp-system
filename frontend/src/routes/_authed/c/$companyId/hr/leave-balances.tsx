import { createFileRoute } from '@tanstack/react-router';
import { LeaveBalancesPage } from '@/modules/hr/leave';

export const Route = createFileRoute('/_authed/c/$companyId/hr/leave-balances')({ component: LeaveBalancesPage });
