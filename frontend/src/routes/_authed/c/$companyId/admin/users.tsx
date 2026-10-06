import { createFileRoute } from '@tanstack/react-router';
import { CompanyUsersPage } from '@/modules/admin/company-users-page';

export const Route = createFileRoute('/_authed/c/$companyId/admin/users')({ component: CompanyUsersPage });
