import { AlertTriangle, Inbox, Loader2, Lock, SearchX } from 'lucide-react';
import type { ReactNode } from 'react';
import { isApiError } from '@/api/errors';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { t } from '@/i18n';
import { cn } from '@/lib/utils';
import { fieldProblemLines, problemMessage } from './problem';

/** A spinner with an accessible label, for whole-page and panel loads. */
export function LoadingState({ label, className }: { label?: string; className?: string }) {
  return (
    <div role="status" aria-live="polite" className={cn('flex items-center justify-center gap-2 p-8 text-sm text-muted-foreground', className)}>
      <Loader2 className="size-4 animate-spin" aria-hidden />
      <span>{label ?? t('app.loading')}</span>
    </div>
  );
}

/** Placeholder rows while a table loads. */
export function SkeletonRows({ rows = 5, columns = 4 }: { rows?: number; columns?: number }) {
  return (
    <div role="status" aria-label={t('app.loading')} className="space-y-2 p-2">
      {Array.from({ length: rows }, (_, row) => (
        <div key={row} className="flex gap-3">
          {Array.from({ length: columns }, (_, column) => (
            <Skeleton key={column} className="h-5 flex-1" />
          ))}
        </div>
      ))}
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
  filtered = false,
  className,
}: {
  title?: string;
  description?: ReactNode;
  action?: ReactNode;
  filtered?: boolean;
  className?: string;
}) {
  const Icon = filtered ? SearchX : Inbox;
  return (
    <div className={cn('flex flex-col items-center justify-center gap-2 px-4 py-10 text-center', className)}>
      <Icon className="size-8 text-muted-foreground" aria-hidden />
      <p className="font-medium">{title ?? (filtered ? t('states.emptyFiltered') : t('states.emptyTitle'))}</p>
      {description ? <p className="max-w-md text-sm text-muted-foreground">{description}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  );
}

/** A failed load: not found, no access, or another problem with its message and a retry. */
export function ErrorState({ error, onRetry, className }: { error: unknown; onRetry?: () => void; className?: string }) {
  const status = isApiError(error) ? error.status : 0;
  if (status === 404) {
    return (
      <EmptyState className={className} title={t('states.notFoundTitle')} description={t('states.notFoundText')} />
    );
  }
  if (status === 403 && isApiError(error) && error.code === 'FORBIDDEN') {
    return (
      <div className={cn('flex flex-col items-center gap-2 px-4 py-10 text-center', className)}>
        <Lock className="size-8 text-muted-foreground" aria-hidden />
        <p className="font-medium">{t('states.forbiddenTitle')}</p>
        <p className="max-w-md text-sm text-muted-foreground">{t('states.forbiddenText')}</p>
      </div>
    );
  }
  return (
    <div className={cn('p-4', className)}>
      <ProblemAlert error={error} action={onRetry ? <Button variant="outline" size="sm" onClick={onRetry}>{t('common.retry')}</Button> : undefined} />
    </div>
  );
}

/** The problem document of a failed action, with field errors that no input could show. */
export function ProblemAlert({
  error,
  action,
  showFieldErrors = true,
  className,
}: {
  error: unknown;
  action?: ReactNode;
  showFieldErrors?: boolean;
  className?: string;
}) {
  if (!error) return null;
  const api = isApiError(error) ? error : null;
  const lines = showFieldErrors && api ? fieldProblemLines(api.fieldErrors) : [];
  return (
    <Alert variant="destructive" className={className}>
      <AlertTriangle aria-hidden />
      <AlertTitle>{problemMessage(error)}</AlertTitle>
      {lines.length > 0 || api?.problem.requestId || action ? (
        <AlertDescription>
          {lines.length > 0 ? (
            <ul className="list-disc pl-4">
              {lines.map((line, i) => (
                <li key={i}>{line}</li>
              ))}
            </ul>
          ) : null}
          {api?.problem.requestId ? (
            <p className="text-xs">{t('states.requestId', { id: api.problem.requestId })}</p>
          ) : null}
          {action ? <div className="mt-2">{action}</div> : null}
        </AlertDescription>
      ) : null}
    </Alert>
  );
}
