import { Link } from '@tanstack/react-router';
import { ArrowLeft } from 'lucide-react';
import type { ReactNode } from 'react';
import { PageHeader, Section } from '@/components/common/page';
import { Money } from '@/components/common/values';
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { t } from '@/i18n';
import { cn } from '@/lib/utils';

/** A link back to the document's list. */
export function BackLink({ to, label }: { to: string; label: string }) {
  return (
    <Link to={to as '/'} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft className="size-4" aria-hidden />
      {label}
    </Link>
  );
}

export interface LineColumnDef<L> {
  id: string;
  header: ReactNode;
  cell: (line: L) => ReactNode;
  align?: 'right';
  hideBelow?: 'sm' | 'md' | 'lg';
  footer?: ReactNode;
}

/** The read-only lines of a document. */
export function LinesTable<L>({ lines, columns, rowKey, caption }: { lines: L[]; columns: LineColumnDef<L>[]; rowKey: (line: L) => string; caption?: string }) {
  const hide = { sm: 'hidden sm:table-cell', md: 'hidden md:table-cell', lg: 'hidden lg:table-cell' };
  const footer = columns.some((c) => c.footer !== undefined);
  return (
    <div className="overflow-x-auto">
      <Table>
        {caption ? <caption className="sr-only">{caption}</caption> : null}
        <TableHeader>
          <TableRow>
            {columns.map((c) => (
              <TableHead key={c.id} scope="col" className={cn(c.align === 'right' && 'text-right', c.hideBelow && hide[c.hideBelow])}>
                {c.header}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {lines.map((line) => (
            <TableRow key={rowKey(line)}>
              {columns.map((c) => (
                <TableCell key={c.id} className={cn(c.align === 'right' && 'text-right tabular', c.hideBelow && hide[c.hideBelow])}>
                  {c.cell(line)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
        {footer ? (
          <TableFooter>
            <TableRow>
              {columns.map((c) => (
                <TableCell key={c.id} className={cn(c.align === 'right' && 'text-right tabular', c.hideBelow && hide[c.hideBelow])}>
                  {c.footer}
                </TableCell>
              ))}
            </TableRow>
          </TableFooter>
        ) : null}
      </Table>
    </div>
  );
}

/** Document totals as computed by the server (G-7). */
export function Totals({ currency, rows }: { currency?: string | null; rows: { label: string; value: string | null | undefined; strong?: boolean }[] }) {
  return (
    <dl className="ml-auto w-full max-w-xs space-y-1 text-sm" aria-label={t('common.totals')}>
      {rows.map((row) => (
        <div key={row.label} className={cn('flex justify-between gap-4', row.strong && 'border-t pt-1 text-base font-semibold')}>
          <dt>{row.label}</dt>
          <dd>
            <Money value={row.value} currency={currency} />
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** The document page pattern: header with state and actions, summary, lines and totals. */
export function DocumentLayout({
  back,
  title,
  badge,
  description,
  actions,
  summary,
  lines,
  linesTitle,
  totals,
  children,
}: {
  back?: ReactNode;
  title: ReactNode;
  badge?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  summary?: ReactNode;
  lines?: ReactNode;
  linesTitle?: string;
  totals?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="space-y-4">
      <PageHeader title={title} badge={badge} description={description} actions={actions} breadcrumbs={back} />
      {summary ? <Section>{summary}</Section> : null}
      {lines ? (
        <Section title={linesTitle ?? t('common.lines')} bodyClassName="p-0">
          {lines}
          {totals ? <div className="border-t p-4">{totals}</div> : null}
        </Section>
      ) : null}
      {children}
    </div>
  );
}
