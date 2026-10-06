import { History } from 'lucide-react';
import { useState } from 'react';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DateTimeText } from '@/components/common/values';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { Drawer } from '@/components/overlay/drawer';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';

type Entry = Schemas['AuditEntryResponse'];

/** Readable "field: value" lines of an audit entry's change details. */
export function changeLines(changes: unknown): string[] {
  if (!changes || typeof changes !== 'object') return [];
  return Object.entries(changes as Record<string, unknown>)
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : String(value)}`);
}

export function AuditEntryItem({ entry }: { entry: Entry }) {
  const lines = changeLines(entry.changes);
  return (
    <li className="space-y-1 border-l-2 border-primary/40 pl-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-medium">{enumLabel(entry.action)}</span>
        <span className="text-xs text-muted-foreground">
          <DateTimeText value={entry.occurredAt} />
        </span>
      </div>
      {entry.fromState || entry.toState ? (
        <div className="text-xs text-muted-foreground">
          {t('audit.fromTo', { from: enumLabel(entry.fromState) || '—', to: enumLabel(entry.toState) || '—' })}
        </div>
      ) : null}
      {lines.length > 0 ? (
        <ul className="space-y-0.5 text-xs break-all text-muted-foreground">
          {lines.slice(0, 12).map((line, i) => (
            <li key={i}>{line}</li>
          ))}
        </ul>
      ) : null}
      {entry.requestId ? <div className="font-mono text-[10px] text-muted-foreground">{entry.requestId}</div> : null}
    </li>
  );
}

/**
 * The change history of one record from the company audit log (G-9). Offered to users who may read
 * the audit log (`admin.audit.read`).
 */
export function AuditHistoryButton({ entityType, entityId }: { entityType: string; entityId: string | undefined }) {
  const { can } = useCompany();
  const [open, setOpen] = useState(false);
  if (!entityId || !can('admin.audit.read')) return null;
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <History aria-hidden />
        {t('audit.history')}
      </Button>
      <Drawer open={open} onOpenChange={setOpen} title={t('audit.title')}>
        {open ? <AuditTimeline entityType={entityType} entityId={entityId} /> : null}
      </Drawer>
    </>
  );
}

function AuditTimeline({ entityType, entityId }: { entityType: string; entityId: string }) {
  const query = useCompanyQuery(['audit', entityType, entityId], (api, signal) =>
    api.get('/audit-log', null, {
      query: { 'filter[entityType]': entityType, 'filter[entityId]': entityId, limit: 100 },
      signal,
    }),
  );
  if (query.isLoading) return <LoadingState />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const entries = query.data?.data ?? [];
  if (entries.length === 0) return <EmptyState title={t('audit.empty')} />;
  return (
    <ol className="space-y-4 text-sm">
      {entries.map((entry) => (
        <AuditEntryItem key={entry.id} entry={entry} />
      ))}
    </ol>
  );
}
