import { createFileRoute } from '@tanstack/react-router';
import { AdminCompaniesPage } from '@/modules/admin/companies-page';

export const Route = createFileRoute('/_authed/admin/companies')({ component: AdminCompaniesPage });
