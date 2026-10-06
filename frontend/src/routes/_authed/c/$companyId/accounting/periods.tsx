import { createFileRoute } from '@tanstack/react-router';
import { PeriodsPage } from '@/modules/accounting/periods';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/periods')({ component: PeriodsPage });
