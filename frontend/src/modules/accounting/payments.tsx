import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import Decimal from 'decimal.js';
import { Plus, Wand2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { companyKey, newIdempotencyKey, useCompanyQuery } from '@/api/hooks';
import { useQueryClient } from '@tanstack/react-query';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Money, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, LinesTable } from '@/components/document/document-layout';
import { EmptyState, ErrorState, LoadingState, ProblemAlert } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, DecimalField, EntityField, FieldGrid, SelectField, TextField } from '@/components/form/fields';
import { DecimalInput } from '@/components/form/inputs';
import { mergePatch, zf } from '@/components/form/schema';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { compareDecimals, isDecimalString, todayIso } from '@/lib/format';
import { cn } from '@/lib/utils';
import { EntryLink } from './entries';

type Payment = Schemas['Payment'];
type OpenItem = Schemas['OpenItem'];

export function PaymentLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/accounting/payments/$paymentId" params={{ companyId, paymentId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

const paymentSchema = z.object({
  direction: zf.id(),
  partnerId: zf.id(),
  bankAccountId: zf.id(),
  paymentDate: zf.date(),
  amount: zf.decimal(),
  method: zf.id(),
  reference: zf.optionalText(100),
  notes: zf.optionalText(1000),
});

export function PaymentsPage() {
  const { api, can, companyId } = useCompany();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('acc.paymentsTitle')} />
      <DataTable<Payment>
        id="payments"
        fetchPage={(c, query, signal) => c.get('/payments', null, { query, signal })}
        rowKey={(p) => p.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'direction', label: t('acc.direction'), values: [...enums.paymentDirection] },
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.paymentStatus] },
          { kind: 'entity', key: 'partnerId', label: t('acc.partner'), source: entities.partner },
          { kind: 'entity', key: 'bankAccountId', label: t('acc.bankAccount'), source: entities.bankAccount },
          { kind: 'dateRange', field: 'paymentDate', label: t('acc.paymentDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (p) => <PaymentLink id={p.id} number={p.number} /> },
          { id: 'direction', header: t('acc.direction'), cell: (p) => enumLabel(p.direction) },
          { id: 'partner', header: t('acc.partner'), cell: (p) => <EntityName source={entities.partner} id={p.partnerId} /> },
          { id: 'date', header: t('acc.paymentDate'), sortKey: 'paymentDate', hideBelow: 'sm', cell: (p) => <DateText value={p.paymentDate} /> },
          { id: 'amount', header: t('common.amount'), align: 'right', cell: (p) => <Money value={p.amount} currency={p.currencyCode} /> },
          { id: 'unallocated', header: t('acc.unallocated'), align: 'right', hideBelow: 'md', cell: (p) => <Money value={p.unallocatedAmount} currency={p.currencyCode} /> },
          { id: 'status', header: t('common.status'), cell: (p) => <StatusBadge status={p.status} /> },
        ]}
        toolbar={can('accounting.payment.create') ? <Button onClick={() => setCreating(true)}><Plus aria-hidden />{t('acc.newPayment')}</Button> : null}
      />
      <FormDialog
        open={creating}
        onOpenChange={setCreating}
        title={t('acc.newPayment')}
        schema={paymentSchema}
        defaults={{ direction: 'INBOUND', partnerId: null, bankAccountId: null, paymentDate: todayIso(), amount: null, method: 'BANK_TRANSFER', reference: '', notes: '' }}
        onSubmit={(v, key) =>
          api.post('/payments', null, {
            body: {
              direction: v.direction!,
              partnerId: v.partnerId!,
              bankAccountId: v.bankAccountId!,
              paymentDate: v.paymentDate ?? undefined,
              amount: v.amount!,
              method: v.method!,
              reference: v.reference || undefined,
              notes: v.notes || undefined,
            },
            idempotencyKey: key,
          })
        }
        onDone={(p) => void navigate({ to: '/c/$companyId/accounting/payments/$paymentId', params: { companyId, paymentId: (p as Payment).id! } })}
      >
        <FieldGrid>
          <SelectField name="direction" label={t('acc.direction')} options={enumOptions(enums.paymentDirection)} required />
          <EntityField name="partnerId" label={t('acc.partner')} source={entities.partner} required />
          <EntityField name="bankAccountId" label={t('acc.bankAccount')} source={entities.bankAccount} required />
          <DateField name="paymentDate" label={t('acc.paymentDate')} required />
          <DecimalField name="amount" label={t('common.amount')} required displayScale={2} />
          <SelectField name="method" label={t('acc.method')} options={enumOptions(enums.paymentMethod)} required />
        </FieldGrid>
        <TextField name="reference" label={t('acc.reference')} />
        <TextField name="notes" label={t('common.notes')} />
      </FormDialog>
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/accounting/payments/$paymentId');

export function PaymentPage() {
  const { paymentId } = route.useParams();
  const { api, companyId, can } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['payments', paymentId], (c, signal) => c.get('/payments/{paymentId}', { paymentId }, { signal }));
  const [allocating, setAllocating] = useState(false);
  const [editing, setEditing] = useState(false);
  const [removing, setRemoving] = useState<Schemas['Allocation'] | null>(null);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const p = query.data;
  const hasUnallocated = isDecimalString(p.unallocatedAmount) && compareDecimals(p.unallocatedAmount, '0') > 0;
  const actions: DocAction<Payment>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: ['accounting.payment.create'], open: () => setEditing(true) },
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: ['accounting.payment.post'],
      confirm: { title: t('doc.postConfirm') },
      run: (c, x, ctx) => c.post('/payments/{paymentId}/post', { paymentId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    { id: 'allocate', label: t('acc.allocate'), primary: true, when: (x) => x.status === 'POSTED' && hasUnallocated, permissions: ['accounting.payment.allocate'], open: () => setAllocating(true) },
    {
      id: 'void',
      label: t('acc.void'),
      variant: 'destructive',
      when: inStatus('POSTED'),
      permissions: ['accounting.payment.void'],
      confirm: { title: t('acc.voidConfirm'), reason: 'required', reasonLabel: t('acc.voidReason'), destructive: true },
      run: (c, x, ctx) => c.post('/payments/{paymentId}/void', { paymentId: x.id! }, { body: { reason: ctx.reason }, ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['accounting.payment.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/payments/{paymentId}', { paymentId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/accounting/payments', params: { companyId } }),
    },
  ];
  const editInitial = { paymentDate: p.paymentDate ?? null, amount: p.amount ?? null, method: p.method ?? null, reference: p.reference ?? '', notes: p.notes ?? '' };
  return (
    <div className="space-y-4">
      <PageHeader
        breadcrumbs={<BackLink to={`/c/${companyId}/accounting/payments`} label={t('acc.paymentsTitle')} />}
        title={p.number ?? t('acc.newPayment')}
        badge={<StatusBadge status={p.status} />}
        description={
          <>
            {enumLabel(p.direction)} · <EntityName source={entities.partner} id={p.partnerId} />
          </>
        }
        actions={
          <>
            <AuditHistoryButton entityType="payment" entityId={p.id} />
            <DocumentActions doc={p} actions={actions} />
          </>
        }
      />
      <Section>
        <DetailList
          items={[
            { label: t('common.amount'), value: <Money value={p.amount} currency={p.currencyCode} className="text-base font-semibold" /> },
            { label: t('acc.unallocated'), value: <Money value={p.unallocatedAmount} currency={p.currencyCode} /> },
            { label: t('acc.paymentDate'), value: <DateText value={p.paymentDate} /> },
            { label: t('acc.bankAccount'), value: <EntityName source={entities.bankAccount} id={p.bankAccountId} /> },
            { label: t('acc.method'), value: enumLabel(p.method) },
            { label: t('acc.reference'), value: <Text value={p.reference} /> },
            p.exchangeRate && p.exchangeRate !== '1' ? { label: t('doc.exchangeRate'), value: p.exchangeRate } : null,
            p.journalEntryId ? { label: t('acc.entry'), value: <EntryLink id={p.journalEntryId} number={t('common.view')} /> } : null,
            p.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={p.postedAt} /> } : null,
            p.voidedReason ? { label: t('acc.voidReason'), value: p.voidedReason } : null,
            p.notes ? { label: t('common.notes'), value: p.notes, wide: true } : null,
          ]}
        />
      </Section>
      <Section title={t('acc.allocations')} bodyClassName="p-0">
        {(p.allocations ?? []).length === 0 ? (
          <EmptyState className="py-6" />
        ) : (
          <LinesTable<Schemas['Allocation']>
            lines={p.allocations ?? []}
            rowKey={(a) => a.id!}
            columns={[
              { id: 'date', header: t('acc.allocationDate'), cell: (a) => <DateText value={a.allocationDate} /> },
              { id: 'item', header: t('acc.openItem'), cell: (a) => <OpenItemLabel id={a.openItemId} direction={p.direction} /> },
              { id: 'amount', header: t('common.amount'), align: 'right', cell: (a) => <Money value={a.amount} currency={p.currencyCode} /> },
              { id: 'fx', header: t('acc.fxDifference'), align: 'right', hideBelow: 'md', cell: (a) => <Money value={a.fxDifferenceBase} showCurrency={false} /> },
              { id: 'state', header: t('common.status'), cell: (a) => (a.reversedAt ? <StatusBadge status="REVERSED" /> : <StatusBadge status="ACTIVE" />) },
              {
                id: 'actions',
                header: <span className="sr-only">{t('common.actions')}</span>,
                cell: (a) =>
                  !a.reversedAt && can('accounting.payment.unallocate') ? (
                    <Button size="sm" variant="ghost" onClick={() => setRemoving(a)}>{t('acc.unallocate')}</Button>
                  ) : null,
              },
            ]}
          />
        )}
      </Section>
      {allocating ? <AllocateDialog payment={p} onClose={() => setAllocating(false)} /> : null}
      <FormDialog
        open={editing}
        onOpenChange={setEditing}
        title={t('doc.edit')}
        schema={z.object({ paymentDate: zf.date(), amount: zf.decimal(), method: zf.id(), reference: zf.optionalText(100), notes: zf.optionalText(1000) })}
        defaults={editInitial}
        success={t('common.saved')}
        onSubmit={(v) => api.patch('/payments/{paymentId}', { paymentId: p.id! }, { body: mergePatch(editInitial, v), ifMatch: p.version })}
      >
        <FieldGrid>
          <DateField name="paymentDate" label={t('acc.paymentDate')} required />
          <DecimalField name="amount" label={t('common.amount')} required displayScale={2} />
          <SelectField name="method" label={t('acc.method')} options={enumOptions(enums.paymentMethod)} required />
          <TextField name="reference" label={t('acc.reference')} />
        </FieldGrid>
        <TextField name="notes" label={t('common.notes')} />
      </FormDialog>
      <UnallocateDialog allocation={removing} onClose={() => setRemoving(null)} />
    </div>
  );
}

function UnallocateDialog({ allocation, onClose }: { allocation: Schemas['Allocation'] | null; onClose: () => void }) {
  const { api } = useCompany();
  // One key per allocation being removed (one user intent).
  const key = useMemo(() => (allocation ? newIdempotencyKey() : ''), [allocation]);
  return (
    <ConfirmDialog
      open={allocation !== null}
      onOpenChange={(open) => !open && onClose()}
      title={t('acc.unallocateConfirm')}
      destructive
      confirmLabel={t('acc.unallocate')}
      onConfirm={() => api.delete('/payment-allocations/{allocationId}', { allocationId: allocation!.id! }, { idempotencyKey: key })}
    />
  );
}

function OpenItemLabel({ id, direction }: { id: string | undefined; direction: string | undefined }) {
  const query = useCompanyQuery(['open-items', direction, id], (c, signal) =>
    direction === 'OUTBOUND' ? c.get('/payables/{itemId}', { itemId: id! }, { signal }) : c.get('/receivables/{itemId}', { itemId: id! }, { signal }),
    { enabled: !!id, retry: false },
  );
  return <Text value={query.data?.item?.documentNumber ?? (id ? `…${id.slice(-6)}` : null)} />;
}

/**
 * Allocates a posted payment to the partner's open items (receivables for incoming, payables for
 * outgoing payments). The sum shown is a display aid; the server checks partner, currency and amounts.
 */
function AllocateDialog({ payment, onClose }: { payment: Payment; onClose: () => void }) {
  const { api, companyId } = useCompany();
  const queryClient = useQueryClient();
  const [key] = useState(newIdempotencyKey);
  const [amounts, setAmounts] = useState<Record<string, string | null>>({});
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const inbound = payment.direction === 'INBOUND';
  const items = useCompanyQuery(['open-items', payment.direction, payment.partnerId, 'allocatable'], (c, signal) => {
    const query = { 'filter[partnerId]': payment.partnerId!, 'filter[status][in]': 'OPEN,PARTIALLY_SETTLED', 'filter[currencyCode]': payment.currencyCode!, sort: 'dueDate', limit: 100 };
    return inbound ? c.get('/receivables', null, { query, signal }) : c.get('/payables', null, { query, signal });
  });
  const rows = (items.data?.data ?? []) as OpenItem[];
  const total = Object.values(amounts).reduce((s, v) => (isDecimalString(v) ? s.plus(v) : s), new Decimal(0));
  const remaining = new Decimal(payment.unallocatedAmount ?? '0').minus(total);

  // Convenience: fill the oldest items first with what is left of the payment.
  const fill = () => {
    let left = new Decimal(payment.unallocatedAmount ?? '0');
    const next: Record<string, string | null> = {};
    for (const item of rows) {
      if (left.lte(0)) break;
      const take = Decimal.min(left, new Decimal(item.openAmount ?? '0'));
      if (take.gt(0)) next[item.id!] = take.toFixed();
      left = left.minus(take);
    }
    setAmounts(next);
  };

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const allocations = Object.entries(amounts)
        .filter(([, v]) => isDecimalString(v) && compareDecimals(v!, '0') > 0)
        .map(([openItemId, amount]) => ({ openItemId, amount: amount! }));
      await api.post('/payments/{paymentId}/allocations', { paymentId: payment.id! }, { body: { allocations }, ifMatch: payment.version, idempotencyKey: key });
      await queryClient.invalidateQueries({ queryKey: companyKey(companyId) });
      notify.success(t('common.saved'));
      onClose();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      onOpenChange={(open) => !open && !busy && onClose()}
      title={t('acc.allocate')}
      description={t('acc.allocateText')}
      size="xl"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>{t('common.cancel')}</Button>
          <Button onClick={() => void submit()} disabled={busy || total.lte(0)}>{t('acc.allocate')}</Button>
        </>
      }
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Button variant="outline" size="sm" onClick={fill}>
          <Wand2 aria-hidden />
          {t('common.apply')}
        </Button>
        <dl className="flex gap-6 text-sm" aria-live="polite">
          <div><dt className="text-xs text-muted-foreground">{t('acc.allocationTotal')}</dt><dd><Money value={total.toFixed()} currency={payment.currencyCode} /></dd></div>
          <div>
            <dt className="text-xs text-muted-foreground">{t('acc.remaining')}</dt>
            <dd className={cn(remaining.lt(0) && 'font-semibold text-status-danger-fg')}><Money value={remaining.toFixed()} currency={payment.currencyCode} /></dd>
          </div>
        </dl>
      </div>
      {items.isLoading ? <LoadingState /> : rows.length === 0 ? <EmptyState /> : (
        <div className="max-h-96 overflow-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('acc.documentNumber')}</TableHead>
                <TableHead>{t('acc.dueDate')}</TableHead>
                <TableHead className="text-right">{t('acc.originalAmount')}</TableHead>
                <TableHead className="text-right">{t('acc.openAmount')}</TableHead>
                <TableHead className="text-right">{t('common.amount')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((item) => (
                <TableRow key={item.id}>
                  <TableCell>{item.documentNumber}</TableCell>
                  <TableCell><DateText value={item.dueDate} /></TableCell>
                  <TableCell className="text-right"><Money value={item.originalAmount} currency={item.currencyCode} /></TableCell>
                  <TableCell className="text-right"><Money value={item.openAmount} currency={item.currencyCode} /></TableCell>
                  <TableCell className="w-40">
                    <DecimalInput value={amounts[item.id!] ?? null} onChange={(v) => setAmounts((a) => ({ ...a, [item.id!]: v }))} aria-label={`${t('common.amount')} ${item.documentNumber}`} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <ProblemAlert error={error} />
    </Modal>
  );
}
