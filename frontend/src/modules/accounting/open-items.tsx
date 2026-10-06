import { useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, Money } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities, type EntitySource } from '@/components/data/entities';
import { LinesTable } from '@/components/document/document-layout';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { DecimalField, EntityField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { Drawer } from '@/components/overlay/drawer';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';
import { enums } from '@/lib/enums';
import { EntryLink } from './entries';
import { PaymentLink } from './payments';

type OpenItem = Schemas['OpenItem'];
type Kind = 'receivables' | 'payables';

/** Open items of one partner and kind, for picking the credit side of a netting. */
function creditItems(kind: Kind, partnerId: string | undefined, currency: string | undefined): EntitySource<OpenItem> {
  return {
    key: `credit-items:${kind}:${partnerId}`,
    mode: 'all',
    list: (c, query, signal) => {
      const q = { ...query, 'filter[partnerId]': partnerId, 'filter[currencyCode]': currency, 'filter[status][in]': 'OPEN,PARTIALLY_SETTLED' };
      return kind === 'receivables' ? c.get('/receivables', null, { query: q, signal }) : c.get('/payables', null, { query: q, signal });
    },
    id: (i) => i.id!,
    label: (i) => `${i.documentNumber} · ${i.openAmount} ${i.currencyCode}`,
    description: (i) => enumLabel(i.sourceType),
  };
}

/** Receivables or payables (open items of AR/AP), with their allocations and credit-note netting. */
export function OpenItemsPage({ kind }: { kind: Kind }) {
  const [selected, setSelected] = useState<OpenItem | null>(null);
  return (
    <div className="space-y-4">
      <PageHeader title={kind === 'receivables' ? t('acc.receivablesTitle') : t('acc.payablesTitle')} />
      <DataTable<OpenItem>
        id={`open-items-${kind}`}
        fetchPage={(c, query, signal) => (kind === 'receivables' ? c.get('/receivables', null, { query, signal }) : c.get('/payables', null, { query, signal }))}
        rowKey={(i) => i.id!}
        defaultSort="dueDate"
        searchable={false}
        onRowOpen={setSelected}
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.openItemStatus] },
          { kind: 'entity', key: 'partnerId', label: t('acc.partner'), source: entities.partner },
          { kind: 'dateRange', field: 'dueDate', label: t('acc.dueDate') },
          { kind: 'text', key: 'documentNumber', label: t('acc.documentNumber') },
        ]}
        columns={[
          {
            id: 'document',
            header: t('acc.documentNumber'),
            cell: (i) => (
              <Button variant="link" className="h-auto p-0" data-row-link onClick={() => setSelected(i)}>
                {i.documentNumber}
              </Button>
            ),
          },
          { id: 'partner', header: t('acc.partner'), cell: (i) => <EntityName source={entities.partner} id={i.partnerId} /> },
          { id: 'date', header: t('acc.documentDate'), sortKey: 'documentDate', hideBelow: 'md', cell: (i) => <DateText value={i.documentDate} /> },
          { id: 'due', header: t('acc.dueDate'), sortKey: 'dueDate', cell: (i) => <DateText value={i.dueDate} /> },
          { id: 'original', header: t('acc.originalAmount'), align: 'right', hideBelow: 'sm', cell: (i) => <Money value={i.originalAmount} currency={i.currencyCode} /> },
          { id: 'open', header: t('acc.openAmount'), align: 'right', cell: (i) => <Money value={i.openAmount} currency={i.currencyCode} /> },
          { id: 'status', header: t('common.status'), cell: (i) => <StatusBadge status={i.status} /> },
        ]}
      />
      <Drawer open={selected !== null} onOpenChange={(open) => !open && setSelected(null)} title={`${t('acc.openItem')} ${selected?.documentNumber ?? ''}`} wide>
        {selected ? <OpenItemDetail kind={kind} itemId={selected.id!} /> : null}
      </Drawer>
    </div>
  );
}

function OpenItemDetail({ kind, itemId }: { kind: Kind; itemId: string }) {
  const { api, can } = useCompany();
  const query = useCompanyQuery(['open-items', kind, itemId], (c, signal) =>
    kind === 'receivables' ? c.get('/receivables/{itemId}', { itemId }, { signal }) : c.get('/payables/{itemId}', { itemId }, { signal }),
  );
  const [netting, setNetting] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} />;
  const item = query.data.item!;
  const open = item.status === 'OPEN' || item.status === 'PARTIALLY_SETTLED';
  return (
    <div className="space-y-4">
      <DetailList
        columns={2}
        items={[
          { label: t('acc.partner'), value: <EntityName source={entities.partner} id={item.partnerId} /> },
          { label: t('common.status'), value: <StatusBadge status={item.status} /> },
          { label: t('acc.documentDate'), value: <DateText value={item.documentDate} /> },
          { label: t('acc.dueDate'), value: <DateText value={item.dueDate} /> },
          { label: t('acc.originalAmount'), value: <Money value={item.originalAmount} currency={item.currencyCode} /> },
          { label: t('acc.openAmount'), value: <Money value={item.openAmount} currency={item.currencyCode} /> },
          { label: t('acc.sourceDocument'), value: `${enumLabel(item.sourceType)} ${item.documentNumber ?? ''}` },
          { label: t('acc.entry'), value: <EntryLink id={item.journalEntryId} number={t('common.view')} /> },
        ]}
      />
      {open && can('accounting.payment.allocate') ? (
        <Button variant="outline" onClick={() => setNetting(true)}>{t('acc.net')}</Button>
      ) : null}
      <h3 className="text-sm font-semibold">{t('acc.allocations')}</h3>
      {(query.data.allocations ?? []).length === 0 ? (
        <EmptyState className="py-4" />
      ) : (
        <LinesTable<Schemas['Allocation']>
          lines={query.data.allocations ?? []}
          rowKey={(a) => a.id!}
          columns={[
            { id: 'date', header: t('acc.allocationDate'), cell: (a) => <DateText value={a.allocationDate} /> },
            { id: 'payment', header: t('acc.payment'), cell: (a) => (a.paymentId ? <PaymentLink id={a.paymentId} number={t('common.view')} /> : t('acc.net')) },
            { id: 'amount', header: t('common.amount'), align: 'right', cell: (a) => <Money value={a.amount} currency={item.currencyCode} /> },
            { id: 'state', header: t('common.status'), cell: (a) => (a.reversedAt ? <StatusBadge status="REVERSED" /> : null) },
          ]}
        />
      )}
      <FormDialog
        open={netting}
        onOpenChange={setNetting}
        title={t('acc.net')}
        description={t('acc.netText')}
        schema={z.object({ creditItemId: zf.id(), amount: zf.decimal() })}
        defaults={{ creditItemId: null, amount: item.openAmount ?? null }}
        success={t('common.saved')}
        onSubmit={(v, key) => api.post('/open-items/net', null, { body: { debitItemId: item.id!, creditItemId: v.creditItemId!, amount: v.amount! }, idempotencyKey: key })}
      >
        <EntityField name="creditItemId" label={t('acc.creditItem')} source={creditItems(kind, item.partnerId, item.currencyCode)} filter={(i) => i.id !== item.id} required />
        <DecimalField name="amount" label={t('common.amount')} required suffix={item.currencyCode} />
      </FormDialog>
    </div>
  );
}

export function ReceivablesPage() {
  return <OpenItemsPage kind="receivables" />;
}

export function PayablesPage() {
  return <OpenItemsPage kind="payables" />;
}
