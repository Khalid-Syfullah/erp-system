import { createFileRoute } from '@tanstack/react-router';
import { NewJournalEntryPage } from '@/modules/accounting/entries';

export const Route = createFileRoute('/_authed/c/$companyId/accounting/entries/new')({ component: NewJournalEntryPage });
