import { createFileRoute } from '@tanstack/react-router';
import { MovementsPage } from '@/modules/inventory/movements';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/movements/')({ component: MovementsPage });
