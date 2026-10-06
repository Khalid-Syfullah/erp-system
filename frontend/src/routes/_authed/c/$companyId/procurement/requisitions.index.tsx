import { createFileRoute } from '@tanstack/react-router';
import { RequisitionsPage } from '@/modules/procurement/requisitions';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/requisitions/')({ component: RequisitionsPage });
