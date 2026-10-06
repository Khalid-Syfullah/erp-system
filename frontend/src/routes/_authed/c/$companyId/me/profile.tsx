import { createFileRoute } from '@tanstack/react-router';
import { MyProfilePage } from '@/modules/hr/self-service';

export const Route = createFileRoute('/_authed/c/$companyId/me/profile')({ component: MyProfilePage });
