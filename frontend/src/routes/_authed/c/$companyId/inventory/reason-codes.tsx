import { createFileRoute } from '@tanstack/react-router';
import { ReasonCodesPage } from '@/modules/inventory/catalog';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/reason-codes')({ component: ReasonCodesPage });
