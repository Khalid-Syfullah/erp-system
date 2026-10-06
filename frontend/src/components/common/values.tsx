import { formatDate, formatDateTime, formatDecimal, formatMoney, formatPercent } from '@/lib/format';
import { cn } from '@/lib/utils';

const dash = <span className="text-muted-foreground">—</span>;

/** An amount in a currency, right-aligned with tabular figures; negative amounts are marked. */
export function Money({
  value,
  currency,
  showCurrency = true,
  className,
}: {
  value: string | number | null | undefined;
  currency?: string | null;
  showCurrency?: boolean;
  className?: string;
}) {
  if (value === null || value === undefined || value === '') return dash;
  const negative = typeof value === 'string' ? value.startsWith('-') : value < 0;
  return (
    <span className={cn('tabular whitespace-nowrap', negative && 'text-status-danger-fg', className)}>
      {formatMoney(value, currency, { showCurrency })}
    </span>
  );
}

export function Quantity({ value, uom, className }: { value: string | null | undefined; uom?: string | null; className?: string }) {
  if (value === null || value === undefined || value === '') return dash;
  return (
    <span className={cn('tabular whitespace-nowrap', className)}>
      {formatDecimal(value)}
      {uom ? <span className="text-muted-foreground"> {uom}</span> : null}
    </span>
  );
}

export function Percent({ value }: { value: string | null | undefined }) {
  if (value === null || value === undefined || value === '') return dash;
  return <span className="tabular whitespace-nowrap">{formatPercent(value)}</span>;
}

export function DateText({ value }: { value: string | null | undefined }) {
  if (!value) return dash;
  return <time dateTime={value} className="whitespace-nowrap">{formatDate(value)}</time>;
}

export function DateTimeText({ value }: { value: string | null | undefined }) {
  if (!value) return dash;
  return <time dateTime={value} className="whitespace-nowrap">{formatDateTime(value)}</time>;
}

export function Text({ value }: { value: string | number | null | undefined }) {
  if (value === null || value === undefined || value === '') return dash;
  return <>{value}</>;
}

export function Code({ children }: { children: string | null | undefined }) {
  if (!children) return dash;
  return <span className="font-mono text-xs">{children}</span>;
}
