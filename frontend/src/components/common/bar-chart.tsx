import Decimal from 'decimal.js';
import { formatDecimal } from '@/lib/format';

export interface BarDatum {
  label: string;
  value: string;
}

/**
 * A horizontal bar chart (dataviz conventions: one measure, sorted, labelled values, zero baseline,
 * one accent colour, no 3-D or legend). It is a list of label/value pairs, so screen readers get the
 * same figures as the bars show.
 */
export function BarChart({ data, title, format = formatDecimal }: { data: BarDatum[]; title: string; format?: (value: string) => string }) {
  const values = data.map((d) => new Decimal(d.value));
  const max = Decimal.max(...values.map((v) => v.abs()), new Decimal(0));
  if (data.length === 0 || max.isZero()) return null;
  return (
    <figure className="space-y-2">
      <figcaption className="text-sm font-medium">{title}</figcaption>
      <ul className="space-y-1" aria-label={title}>
        {data.map((d, i) => {
          const width = values[i]!.abs().div(max).times(100).toNumber();
          return (
            <li key={`${d.label}-${i}`} className="grid grid-cols-[minmax(6rem,12rem)_1fr_auto] items-center gap-2 text-xs">
              <span className="truncate" title={d.label}>{d.label}</span>
              <span className="h-4 rounded-sm bg-muted" aria-hidden>
                <span className="block h-full rounded-sm bg-chart-1" style={{ width: `${Math.max(width, 0.5)}%` }} />
              </span>
              <span className="tabular text-right">{format(d.value)}</span>
            </li>
          );
        })}
      </ul>
    </figure>
  );
}
