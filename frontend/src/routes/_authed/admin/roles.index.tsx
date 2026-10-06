import { createFileRoute } from '@tanstack/react-router';
import { AdminRolesPage } from '@/modules/admin/roles-page';

export const Route = createFileRoute('/_authed/admin/roles/')({ component: AdminRolesPage });
