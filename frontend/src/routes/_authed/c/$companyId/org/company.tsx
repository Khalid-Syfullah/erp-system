import { createFileRoute } from '@tanstack/react-router';
import { CompanyPage } from '@/modules/org/company-page';

export const Route = createFileRoute('/_authed/c/$companyId/org/company')({ component: CompanyPage });
