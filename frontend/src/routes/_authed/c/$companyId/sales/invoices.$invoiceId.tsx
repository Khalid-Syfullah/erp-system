import { createFileRoute } from '@tanstack/react-router';
import { InvoicePage } from '@/modules/sales/invoices';

export const Route = createFileRoute('/_authed/c/$companyId/sales/invoices/$invoiceId')({ component: InvoicePage });
