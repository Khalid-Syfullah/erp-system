import { createFileRoute } from '@tanstack/react-router';
import { JournalEntriesPage } from '@/modules/accounting/entries';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/entries/')({ component: JournalEntriesPage });
