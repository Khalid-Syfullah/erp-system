import { createFileRoute } from '@tanstack/react-router';
import { PositionsPage } from '@/modules/hr/hr-master';

export const Route = createFileRoute('/_authed/c/$companyId/hr/positions')({ component: PositionsPage });
