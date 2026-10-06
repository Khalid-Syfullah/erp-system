import { createFileRoute } from '@tanstack/react-router';
import { OrganizationPage } from '@/modules/hr/hr-master';

export const Route = createFileRoute('/_authed/c/$companyId/hr/organization')({ component: OrganizationPage });
