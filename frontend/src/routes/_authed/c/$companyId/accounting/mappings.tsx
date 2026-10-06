import { createFileRoute } from '@tanstack/react-router';
import { AccountMappingsPage } from '@/modules/accounting/setup';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/mappings')({ component: AccountMappingsPage });
