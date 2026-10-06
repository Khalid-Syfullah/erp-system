import { createFileRoute } from '@tanstack/react-router';
import { AdminUsersPage } from '@/modules/admin/users-page';

export const Route = createFileRoute('/_authed/admin/users/')({ component: AdminUsersPage });
