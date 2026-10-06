import { createFileRoute } from '@tanstack/react-router';
import { NewRequisitionPage } from '@/modules/procurement/requisitions';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/requisitions/new')({ component: NewRequisitionPage });
