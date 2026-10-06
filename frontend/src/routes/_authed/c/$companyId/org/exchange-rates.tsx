import { createFileRoute } from '@tanstack/react-router';
import { ExchangeRatesPage } from '@/modules/org/master-pages';

export const Route = createFileRoute('/_authed/c/$companyId/org/exchange-rates')({ component: ExchangeRatesPage });
