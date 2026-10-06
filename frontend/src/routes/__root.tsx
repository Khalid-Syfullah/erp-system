import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, Link, Outlet } from '@tanstack/react-router';
import { EmptyState, ErrorState } from '@/components/feedback/states';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';

export const Route = createRootRouteWithContext<{ queryClient: QueryClient }>()({
  component: Outlet,
  notFoundComponent: () => (
    <EmptyState
      className="min-h-[50vh]"
      title={t('states.notFoundTitle')}
      description={t('states.notFoundText')}
      action={
        <Button asChild variant="outline">
          <Link to="/">{t('common.back')}</Link>
        </Button>
      }
    />
  ),
  errorComponent: ({ error, reset }) => <ErrorState error={error} onRetry={reset} className="min-h-[50vh]" />,
});
