import { createFileRoute } from '@tanstack/react-router';
import { NewSupplierBillPage } from '@/modules/procurement/bills';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/bills/new')({ component: NewSupplierBillPage });
