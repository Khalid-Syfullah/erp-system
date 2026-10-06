import { createFileRoute } from '@tanstack/react-router';
import { NewInvoicePage } from '@/modules/sales/invoices';

export const Route = createFileRoute('/_authed/c/$companyId/sales/invoices/new')({ component: NewInvoicePage });
