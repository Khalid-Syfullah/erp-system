import { createFileRoute } from '@tanstack/react-router';
import { NewMovementPage } from '@/modules/inventory/movements';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/movements/new')({ component: NewMovementPage });
