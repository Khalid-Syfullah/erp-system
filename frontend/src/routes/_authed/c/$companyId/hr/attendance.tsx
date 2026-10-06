import { createFileRoute } from '@tanstack/react-router';
import { AttendancePage } from '@/modules/hr/attendance';

export const Route = createFileRoute('/_authed/c/$companyId/hr/attendance')({ component: AttendancePage });
