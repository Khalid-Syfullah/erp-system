import { createFileRoute } from '@tanstack/react-router';
import { PartnersPage } from '@/modules/org/partners';

export const Route = createFileRoute('/_authed/c/$companyId/org/partners/')({ component: PartnersPage });
