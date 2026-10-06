import { createFileRoute } from '@tanstack/react-router';
import { PayablesPage } from '@/modules/accounting/open-items';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/payables')({ component: PayablesPage });
