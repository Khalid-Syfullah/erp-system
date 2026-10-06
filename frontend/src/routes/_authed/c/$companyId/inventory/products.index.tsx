import { createFileRoute } from '@tanstack/react-router';
import { ProductsPage } from '@/modules/inventory/catalog';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/products/')({ component: ProductsPage });
