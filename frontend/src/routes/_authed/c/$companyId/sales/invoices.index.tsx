import { createFileRoute } from '@tanstack/react-router';
import { InvoicesPage } from '@/modules/sales/invoices';

export const Route = createFileRoute('/_authed/c/$companyId/sales/invoices/')({ component: InvoicesPage });
