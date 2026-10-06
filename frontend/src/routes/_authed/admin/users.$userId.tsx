import { createFileRoute } from '@tanstack/react-router';
import { AdminUserPage } from '@/modules/admin/user-page';

export const Route = createFileRoute('/_authed/admin/users/$userId')({ component: AdminUserPage });
