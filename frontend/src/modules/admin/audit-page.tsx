import { useState } from 'react';
import { api, type CompanyApi, type Query, type Schemas } from '@/api/client';
import { PageHeader } from '@/components/common/page';
import { DateTimeText } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { AuditEntryItem } from '@/components/document/audit-panel';
import { Drawer } from '@/components/overlay/drawer';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';

type Entry = Schemas['AuditEntryResponse'];

const MODULES = ['auth', 'org', 'partners', 'inventory', 'procurement', 'sales', 'accounting', 'hr', 'payroll', 'reporting', 'admin'];

/** The audit log (SECURITY.md §8): of one company, or across companies for system administrators. */
function AuditLog({ id, title, fetchPage }: { id: string; title: string; fetchPage: (api: CompanyApi, query: Query, signal: AbortSignal) => Promise<{ data?: Entry[] }> }) {
  const [selected, setSelected] = useState<Entry | null>(null);
  return (
    <div className="space-y-4">
      <PageHeader title={title} />
      <DataTable<Entry>
        id={id}
        fetchPage={fetchPage}
        rowKey={(e) => e.id!}
        searchable={false}
        defaultSort="-occurredAt"
        filters={[
          { kind: 'select', key: 'module', label: t('audit.module'), options: MODULES.map((m) => ({ value: m, label: m })) },
          { kind: 'text', key: 'entityType', label: t('audit.entity') },
          { kind: 'text', key: 'action', label: t('audit.action') },
          { kind: 'date', key: 'occurredAt.gte', label: t('common.from') },
          { kind: 'date', key: 'occurredAt.lt', label: t('common.to') },
        ]}
        onRowOpen={setSelected}
        columns={[
          { id: 'when', header: t('audit.occurredAt'), sortKey: 'occurredAt', cell: (e) => <DateTimeText value={e.occurredAt} /> },
          {
            id: 'action',
            header: t('audit.action'),
            cell: (e) => (
              <Button variant="link" className="h-auto p-0" data-row-link onClick={() => setSelected(e)}>
                {enumLabel(e.action)}
              </Button>
            ),
          },
          { id: 'module', header: t('audit.module'), hideBelow: 'sm', cell: (e) => e.module },
          {
            id: 'entity',
            header: t('audit.entity'),
            cell: (e) => (
              <span>
                <span className="text-muted-foreground">{e.entityType}</span> {e.entityLabel}
              </span>
            ),
          },
          { id: 'actor', header: t('audit.actor'), hideBelow: 'md', cell: (e) => <span className="font-mono text-xs">{e.actorType === 'SYSTEM' || !e.actorUserId ? t('audit.system') : `…${e.actorUserId.slice(-8)}`}</span> },
        ]}
      />
      <Drawer open={selected !== null} onOpenChange={(open) => !open && setSelected(null)} title={t('audit.title')}>
        {selected ? (
          <ol>
            <AuditEntryItem entry={selected} />
          </ol>
        ) : null}
      </Drawer>
    </div>
  );
}

export function GlobalAuditPage() {
  return <AuditLog id="global-audit" title={t('admin.auditLog')} fetchPage={(_, query, signal) => api.get('/api/v1/admin/audit-log', {}, { query, signal })} />;
}

export function CompanyAuditPage() {
  return <AuditLog id="company-audit" title={t('nav.auditLog')} fetchPage={(c, query, signal) => c.get('/audit-log', null, { query, signal })} />;
}
