import { createFileRoute } from '@tanstack/react-router';
import { MovementPage } from '@/modules/inventory/movements';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/movements/$movementId')({ component: MovementPage });
