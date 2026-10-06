import { createFileRoute } from '@tanstack/react-router';
import { RequisitionPage } from '@/modules/procurement/requisitions';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/requisitions/$requisitionId')({ component: RequisitionPage });
