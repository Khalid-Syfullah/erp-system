import { createFileRoute } from '@tanstack/react-router';
import { PartnerGroupsPage } from '@/modules/org/master-pages';

export const Route = createFileRoute('/_authed/c/$companyId/org/partner-groups')({ component: PartnerGroupsPage });
