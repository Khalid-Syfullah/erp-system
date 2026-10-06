import { createFileRoute } from '@tanstack/react-router';
import { CategoriesPage } from '@/modules/inventory/catalog';

export const Route = createFileRoute('/_authed/c/$companyId/inventory/categories')({ component: CategoriesPage });
