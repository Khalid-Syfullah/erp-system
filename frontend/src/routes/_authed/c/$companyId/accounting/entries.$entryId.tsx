import { createFileRoute } from '@tanstack/react-router';
import { JournalEntryPage } from '@/modules/accounting/entries';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/entries/$entryId')({ component: JournalEntryPage });
