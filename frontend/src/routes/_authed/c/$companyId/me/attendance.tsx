import { createFileRoute } from '@tanstack/react-router';
import { MyAttendancePage } from '@/modules/hr/self-service';

export const Route = createFileRoute('/_authed/c/$companyId/me/attendance')({ component: MyAttendancePage });
