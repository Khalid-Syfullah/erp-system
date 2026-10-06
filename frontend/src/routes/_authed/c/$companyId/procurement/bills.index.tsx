import { createFileRoute } from '@tanstack/react-router';
import { SupplierBillsPage } from '@/modules/procurement/bills';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/bills/')({ component: SupplierBillsPage });
