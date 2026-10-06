import { createFileRoute } from '@tanstack/react-router';
import { CountPage } from '@/modules/inventory/counts';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/counts/$countId')({ component: CountPage });
