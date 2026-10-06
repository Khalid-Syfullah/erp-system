import { createFileRoute } from '@tanstack/react-router';
import { SuppliersPage } from '@/modules/org/partners';

export const Route = createFileRoute('/_authed/c/$companyId/procurement/suppliers')({ component: SuppliersPage });
