import { createFileRoute } from '@tanstack/react-router';
import { GlobalAuditPage } from '@/modules/admin/audit-page';

export const Route = createFileRoute('/_authed/admin/audit')({ component: GlobalAuditPage });
