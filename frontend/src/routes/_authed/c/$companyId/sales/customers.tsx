import { createFileRoute } from '@tanstack/react-router';
import { CustomersPage } from '@/modules/org/partners';

export const Route = createFileRoute('/_authed/c/$companyId/sales/customers')({ component: CustomersPage });
