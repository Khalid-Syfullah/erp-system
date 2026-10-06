import { createFileRoute } from '@tanstack/react-router';
import { TaxCodesPage } from '@/modules/org/master-pages';

export const Route = createFileRoute('/_authed/c/$companyId/org/tax-codes')({ component: TaxCodesPage });
