import { createFileRoute } from '@tanstack/react-router';
import { MyLeavePage } from '@/modules/hr/self-service';

export const Route = createFileRoute('/_authed/c/$companyId/me/leave')({ component: MyLeavePage });
