import { createFileRoute } from '@tanstack/react-router';
import { CountsPage } from '@/modules/inventory/counts';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/counts/')({ component: CountsPage });
