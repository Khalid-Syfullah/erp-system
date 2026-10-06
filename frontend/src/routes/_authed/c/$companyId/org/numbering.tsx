import { createFileRoute } from '@tanstack/react-router';
import { NumberingPage } from '@/modules/org/company-page';

export const Route = createFileRoute('/_authed/c/$companyId/org/numbering')({ component: NumberingPage });
