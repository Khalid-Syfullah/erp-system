import { createFileRoute } from '@tanstack/react-router';
import { CompanyAuditPage } from '@/modules/admin/audit-page';

export const Route = createFileRoute('/_authed/c/$companyId/admin/audit')({ component: CompanyAuditPage });
