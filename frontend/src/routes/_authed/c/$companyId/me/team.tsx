import { createFileRoute } from '@tanstack/react-router';
import { MyTeamPage } from '@/modules/hr/self-service';

export const Route = createFileRoute('/_authed/c/$companyId/me/team')({ component: MyTeamPage });
