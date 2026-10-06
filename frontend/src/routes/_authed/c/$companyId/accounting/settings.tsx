import { createFileRoute } from '@tanstack/react-router';
import { AccountingSettingsPage } from '@/modules/accounting/setup';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/settings')({ component: AccountingSettingsPage });
