import { createFileRoute } from '@tanstack/react-router';
import { ProductPage } from '@/modules/inventory/catalog';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/products/$productId')({ component: ProductPage });
