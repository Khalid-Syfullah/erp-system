import { createFileRoute } from '@tanstack/react-router';
import { AdminRolePage } from '@/modules/admin/roles-page';

export const Route = createFileRoute('/_authed/admin/roles/$roleId')({ component: AdminRolePage });
