import { createFileRoute } from '@tanstack/react-router';
import { PartnerPage } from '@/modules/org/partners';

export const Route = createFileRoute('/_authed/c/$companyId/org/partners/$partnerId')({ component: PartnerPage });
