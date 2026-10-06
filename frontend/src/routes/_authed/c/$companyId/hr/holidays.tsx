import { createFileRoute } from '@tanstack/react-router';
import { HolidaysPage } from '@/modules/hr/hr-master';

export const Route = createFileRoute('/_authed/c/$companyId/hr/holidays')({ component: HolidaysPage });
