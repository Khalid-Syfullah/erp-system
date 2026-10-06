import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

/** The title row of a page: title, subtitle/status, and the page's primary actions. */
export function PageHeader({
  title,
  description,
  badge,
  actions,
  breadcrumbs,
}: {
  title: ReactNode;
  description?: ReactNode;
  badge?: ReactNode;
  actions?: ReactNode;
  breadcrumbs?: ReactNode;
}) {
  return (
    <header className="mb-4 space-y-2">
      {breadcrumbs}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
            {badge}
          </div>
          {description ? <div className="text-sm text-muted-foreground">{description}</div> : null}
        </div>
        {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
    </header>
  );
}

/** A titled card section of a page. */
export function Section({
  title,
  actions,
  children,
  className,
  bodyClassName,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  bodyClassName?: string;
}) {
  return (
    <section className={cn('rounded-lg border bg-card text-card-foreground', className)}>
      {title || actions ? (
        <div className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2.5">
          {title ? <h2 className="text-sm font-semibold">{title}</h2> : <span />}
          {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
        </div>
      ) : null}
      <div className={cn('p-4', bodyClassName)}>{children}</div>
    </section>
  );
}

export interface DetailItem {
  label: ReactNode;
  value: ReactNode;
  wide?: boolean;
}

/** Read-only fields of a record as a responsive description list. */
export function DetailList({ items, columns = 3 }: { items: (DetailItem | false | null | undefined)[]; columns?: 2 | 3 | 4 }) {
  const grid = { 2: 'sm:grid-cols-2', 3: 'sm:grid-cols-2 lg:grid-cols-3', 4: 'sm:grid-cols-2 lg:grid-cols-4' }[columns];
  return (
    <dl className={cn('grid grid-cols-1 gap-x-6 gap-y-3', grid)}>
      {items.filter(Boolean).map((item, index) => {
        const { label, value, wide } = item as DetailItem;
        return (
          <div key={index} className={cn('min-w-0', wide && 'sm:col-span-full')}>
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className="mt-0.5 text-sm break-words">{value}</dd>
          </div>
        );
      })}
    </dl>
  );
}

/** A page body that is narrower for forms. */
export function PageBody({ children, narrow }: { children: ReactNode; narrow?: boolean }) {
  return <div className={cn('space-y-4', narrow && 'max-w-3xl')}>{children}</div>;
}
