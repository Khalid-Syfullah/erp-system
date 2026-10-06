import { createFileRoute } from '@tanstack/react-router';
import { SupplierBillPage } from '@/modules/procurement/bills';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/bills/$billId')({ component: SupplierBillPage });
