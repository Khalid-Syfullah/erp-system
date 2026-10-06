import { createFileRoute } from '@tanstack/react-router';
import { BranchesPage } from '@/modules/org/master-pages';

export const Route = createFileRoute('/_authed/c/$companyId/org/branches')({ component: BranchesPage });
