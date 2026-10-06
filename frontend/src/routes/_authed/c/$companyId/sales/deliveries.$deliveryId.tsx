import { createFileRoute } from '@tanstack/react-router';
import { DeliveryPage } from '@/modules/sales/deliveries';

export const Route = createFileRoute('/_authed/c/$companyId/sales/deliveries/$deliveryId')({ component: DeliveryPage });
