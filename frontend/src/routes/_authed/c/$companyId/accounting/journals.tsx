import { createFileRoute } from '@tanstack/react-router';
import { JournalsPage } from '@/modules/accounting/setup';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/journals')({ component: JournalsPage });
