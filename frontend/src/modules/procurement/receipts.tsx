import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Plus, Save } from 'lucide-react';
import { useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Money, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName, EntityPicker } from '@/components/data/entity';
import { documents, entities, locationById, locationSource } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable } from '@/components/document/document-layout';
import { LineDecimal } from '@/components/document/lines-editor';
import { ErrorState, LoadingState, ProblemAlert } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, EntityField, TextareaField, TextField } from '@/components/form/fields';
import { DecimalInput } from '@/components/form/inputs';
import { zf } from '@/components/form/schema';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { t } from '@/i18n';
import { enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { PurchaseOrderLink } from './purchase-orders';

type Receipt = Schemas['GoodsReceipt'];
type ReceiptLine = Schemas['GoodsReceiptLine'];

export function ReceiptLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/procurement/receipts/$receiptId" params={{ companyId, receiptId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

const newReceiptSchema = z.object({ purchaseOrderId: zf.id(), receiptDate: zf.date(), supplierDeliveryNote: zf.optionalText(100) });

export function GoodsReceiptsPage() {
  const { api, can, companyId } = useCompany();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const create = can('procurement.receipt.create') ? (
    <Button onClick={() => setCreating(true)}>
      <Plus aria-hidden />
      {t('proc.newReceipt')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.receiptsTitle')} />
      <DataTable<Receipt>
        id="goods-receipts"
        fetchPage={(c, query, signal) => c.get('/goods-receipts', null, { query, signal })}
        rowKey={(r) => r.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.documentStatus] },
          { kind: 'entity', key: 'supplierId', label: t('proc.supplier'), source: entities.supplier },
          { kind: 'entity', key: 'warehouseId', label: t('common.warehouse'), source: entities.warehouse },
          { kind: 'dateRange', field: 'receiptDate', label: t('proc.receiptDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (r) => <ReceiptLink id={r.id} number={r.number} /> },
          { id: 'order', header: t('proc.order'), cell: (r) => <EntityName source={documents.purchaseOrder} id={r.purchaseOrderId} /> },
          { id: 'supplier', header: t('proc.supplier'), hideBelow: 'sm', cell: (r) => <EntityName source={entities.supplier} id={r.supplierId} /> },
          { id: 'date', header: t('proc.receiptDate'), sortKey: 'receiptDate', hideBelow: 'md', cell: (r) => <DateText value={r.receiptDate} /> },
          { id: 'warehouse', header: t('common.warehouse'), hideBelow: 'lg', cell: (r) => <EntityName source={entities.warehouse} id={r.warehouseId} /> },
          { id: 'status', header: t('common.status'), cell: (r) => <StatusBadge status={r.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
      <FormDialog
        open={creating}
        onOpenChange={setCreating}
        title={t('proc.newReceipt')}
        description={t('proc.receiptText')}
        schema={newReceiptSchema}
        defaults={{ purchaseOrderId: null, receiptDate: todayIso(), supplierDeliveryNote: '' }}
        onSubmit={(v) =>
          api.post('/goods-receipts', null, { body: { purchaseOrderId: v.purchaseOrderId!, receiptDate: v.receiptDate ?? undefined, supplierDeliveryNote: v.supplierDeliveryNote || undefined } })
        }
        onDone={(r) => void navigate({ to: '/c/$companyId/procurement/receipts/$receiptId', params: { companyId, receiptId: (r as Receipt).id! } })}
      >
        <EntityField name="purchaseOrderId" label={t('proc.order')} source={documents.purchaseOrder} filters={{ 'status.in': ['APPROVED', 'PARTIALLY_RECEIVED'] }} required />
        <DateField name="receiptDate" label={t('proc.receiptDate')} required />
        <TextField name="supplierDeliveryNote" label={t('proc.deliveryNote')} />
      </FormDialog>
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/procurement/receipts/$receiptId');

/**
 * A goods receipt. While it is a draft, the quantities and locations of its lines are edited in
 * large touch-friendly rows (the receiving dock works on tablets); posting moves the stock.
 */
export function GoodsReceiptPage() {
  const { receiptId } = route.useParams();
  const { api, companyId, can } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['goods-receipts', receiptId], (c, signal) => c.get('/goods-receipts/{receiptId}', { receiptId }, { signal }));
  const [changes, setChanges] = useState<Record<string, { quantity?: string | null; locationId?: string | null }>>({});
  const [saveError, setSaveError] = useState<unknown>(null);
  const [returning, setReturning] = useState(false);
  const [billing, setBilling] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const r = query.data;
  const draft = r.status === 'DRAFT' && can('procurement.receipt.create');
  const dirty = Object.keys(changes).length > 0;

  const saveLines = async () => {
    setSaveError(null);
    try {
      await api.patch('/goods-receipts/{receiptId}', { receiptId: r.id! }, {
        body: {
          lines: (r.lines ?? []).map((l) => ({
            purchaseOrderLineId: l.purchaseOrderLineId,
            quantity: changes[l.id!]?.quantity ?? l.quantity,
            uomId: l.uomId,
            locationId: changes[l.id!]?.locationId ?? l.locationId,
          })),
        },
        ifMatch: r.version,
      });
      setChanges({});
      notify.success(t('common.saved'));
      await query.refetch();
    } catch (e) {
      setSaveError(e);
    }
  };

  const actions: DocAction<Receipt>[] = [
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: (x) => x.status === 'DRAFT' && !dirty,
      permissions: ['procurement.receipt.post'],
      confirm: { title: t('proc.postReceiptConfirm') },
      run: (c, x, ctx) => c.post('/goods-receipts/{receiptId}/post', { receiptId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    { id: 'bill', label: t('proc.createBill'), when: inStatus('POSTED'), permissions: ['procurement.supplier_bill.create'], open: () => setBilling(true) },
    { id: 'return', label: t('proc.newReturn'), when: inStatus('POSTED'), permissions: ['procurement.return.manage'], open: () => setReturning(true) },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['procurement.receipt.post'],
      confirm: { title: t('doc.cancel'), destructive: true },
      run: (c, x) => c.post('/goods-receipts/{receiptId}/cancel', { receiptId: x.id! }, { ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['procurement.receipt.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/goods-receipts/{receiptId}', { receiptId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/procurement/receipts', params: { companyId } }),
    },
  ];

  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/procurement/receipts`} label={t('proc.receiptsTitle')} />}
      title={r.number ?? t('proc.newReceipt')}
      badge={<StatusBadge status={r.status} />}
      description={<EntityName source={entities.supplier} id={r.supplierId} />}
      actions={
        <>
          <AuditHistoryButton entityType="goods_receipt" entityId={r.id} />
          {draft ? (
            <Button variant="outline" onClick={() => void saveLines()} disabled={!dirty}>
              <Save aria-hidden />
              {t('common.save')}
            </Button>
          ) : null}
          <DocumentActions doc={r} actions={actions} />
        </>
      }
      summary={
        <DetailList
          items={[
            { label: t('proc.order'), value: <PurchaseOrderLink id={r.purchaseOrderId} number={t('common.view')} /> },
            { label: t('proc.receiptDate'), value: <DateText value={r.receiptDate} /> },
            { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={r.warehouseId} /> },
            { label: t('proc.deliveryNote'), value: <Text value={r.supplierDeliveryNote} /> },
            r.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={r.postedAt} /> } : null,
            r.notes ? { label: t('doc.notes'), value: r.notes, wide: true } : null,
          ]}
        />
      }
    >
      {saveError ? <ProblemAlert error={saveError} /> : null}
      {draft ? (
        <ul className="space-y-3" aria-label={t('common.lines')}>
          {(r.lines ?? []).map((l) => (
            <li key={l.id} className="grid gap-3 rounded-lg border bg-card p-4 sm:grid-cols-[1fr_10rem_16rem] sm:items-end">
              <div>
                <div className="font-medium">{l.lineNo}. <EntityName source={entities.variant} id={l.variantId} /></div>
                <div className="text-sm text-muted-foreground"><EntityName source={entities.uom} id={l.uomId} /></div>
              </div>
              <div className="space-y-1">
                <Label htmlFor={`qty-${l.id}`} className="text-xs">{t('common.quantity')}</Label>
                <DecimalInput
                  id={`qty-${l.id}`}
                  value={changes[l.id!]?.quantity ?? l.quantity}
                  onChange={(v) => setChanges((c) => ({ ...c, [l.id!]: { ...c[l.id!], quantity: v } }))}
                  className="[&_input]:h-11 [&_input]:text-lg"
                />
              </div>
              <div className="space-y-1">
                <Label id={`loc-${l.id}`} className="text-xs">{t('inv.location')}</Label>
                <EntityPicker
                  source={locationSource(r.warehouseId)}
                  value={changes[l.id!]?.locationId ?? l.locationId}
                  onChange={(v) => setChanges((c) => ({ ...c, [l.id!]: { ...c[l.id!], locationId: v } }))}
                  aria-labelledby={`loc-${l.id}`}
                  clearable={false}
                  size="lg"
                />
              </div>
            </li>
          ))}
        </ul>
      ) : (
        <Section title={t('common.lines')} bodyClassName="p-0">
          <LinesTable<ReceiptLine>
            lines={r.lines ?? []}
            rowKey={(l) => l.id!}
            columns={[
              { id: 'no', header: '#', cell: (l) => l.lineNo },
              { id: 'item', header: t('doc.item'), cell: (l) => <EntityName source={entities.variant} id={l.variantId} /> },
              { id: 'location', header: t('inv.location'), hideBelow: 'sm', cell: (l) => <EntityName source={locationById} id={l.locationId} /> },
              { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
              { id: 'uom', header: t('inv.uom'), cell: (l) => <EntityName source={entities.uom} id={l.uomId} /> },
              { id: 'cost', header: t('inv.unitCost'), align: 'right', hideBelow: 'md', cell: (l) => <Money value={l.unitCostDoc} currency={r.currencyCode} showCurrency={false} /> },
              { id: 'value', header: t('inv.value'), align: 'right', hideBelow: 'md', cell: (l) => <Money value={l.valueBase} showCurrency={false} /> },
              { id: 'billed', header: t('doc.billed'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.billedQuantityBase} /> },
              { id: 'returned', header: t('doc.returned'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.returnedQuantityBase} /> },
            ]}
          />
        </Section>
      )}
      <FormDialog
        open={billing}
        onOpenChange={setBilling}
        title={t('proc.billFromReceipts')}
        schema={z.object({ supplierInvoiceNumber: zf.text(50), billDate: zf.date() })}
        defaults={{ supplierInvoiceNumber: '', billDate: todayIso() }}
        onSubmit={(v) => api.post('/supplier-bills/from-receipts', null, { body: { goodsReceiptIds: [r.id!], supplierInvoiceNumber: v.supplierInvoiceNumber, billDate: v.billDate ?? undefined } })}
        onDone={(bill) => void navigate({ to: '/c/$companyId/procurement/bills/$billId', params: { companyId, billId: (bill as Schemas['SupplierBill']).id! } })}
      >
        <TextField name="supplierInvoiceNumber" label={t('proc.supplierInvoiceNumber')} required />
        <DateField name="billDate" label={t('proc.billDate')} required />
      </FormDialog>
      {returning ? <ReturnDialog receipt={r} onClose={() => setReturning(false)} /> : null}
    </DocumentLayout>
  );
}

const returnSchema = z.object({
  returnDate: zf.date(),
  reason: zf.text(500),
  lines: z.array(z.object({ goodsReceiptLineId: z.string(), quantity: zf.optionalDecimal() })),
});

function ReturnDialog({ receipt, onClose }: { receipt: Receipt; onClose: () => void }) {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const lines = receipt.lines ?? [];
  return (
    <FormDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('proc.newReturn')}
      size="lg"
      schema={returnSchema}
      defaults={{ returnDate: todayIso(), reason: '', lines: lines.map((l) => ({ goodsReceiptLineId: l.id!, quantity: null })) }}
      onSubmit={(v) =>
        api.post('/purchase-returns', null, {
          body: {
            goodsReceiptId: receipt.id!,
            returnDate: v.returnDate ?? undefined,
            reason: v.reason,
            lines: v.lines.filter((l) => l.quantity).map((l) => ({ goodsReceiptLineId: l.goodsReceiptLineId, quantity: l.quantity! })),
          },
        })
      }
      onDone={(ret) => void navigate({ to: '/c/$companyId/procurement/returns/$returnId', params: { companyId, returnId: (ret as Schemas['PurchaseReturn']).id! } })}
    >
      <DateField name="returnDate" label={t('proc.returnDate')} required />
      <TextareaField name="reason" label={t('proc.returnReason')} required rows={2} />
      <ul className="space-y-2">
        {lines.map((l, i) => (
          <li key={l.id} className="grid items-center gap-2 sm:grid-cols-[1fr_10rem]">
            <span className="text-sm">
              <EntityName source={entities.variant} id={l.variantId} /> · <Quantity value={l.quantity} />
            </span>
            <LineDecimal name={`lines.${i}.quantity`} label={`${t('common.quantity')} ${i + 1}`} />
          </li>
        ))}
      </ul>
    </FormDialog>
  );
}

// --- Purchase returns -------------------------------------------------------------------------

type Return = Schemas['PurchaseReturn'];

export function PurchaseReturnsPage() {
  const { companyId } = useCompany();
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.returnsTitle')} />
      <DataTable<Return>
        id="purchase-returns"
        fetchPage={(c, query, signal) => c.get('/purchase-returns', null, { query, signal })}
        rowKey={(r) => r.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.documentStatus] },
          { kind: 'entity', key: 'supplierId', label: t('proc.supplier'), source: entities.supplier },
        ]}
        columns={[
          {
            id: 'number',
            header: t('common.number'),
            cell: (r) => (
              <Link to="/c/$companyId/procurement/returns/$returnId" params={{ companyId, returnId: r.id! }} data-row-link className="font-medium text-primary hover:underline">
                {r.number ?? t('doc.draft')}
              </Link>
            ),
          },
          { id: 'receipt', header: t('proc.receipt'), cell: (r) => <EntityName source={documents.goodsReceipt} id={r.goodsReceiptId} /> },
          { id: 'supplier', header: t('proc.supplier'), hideBelow: 'sm', cell: (r) => <EntityName source={entities.supplier} id={r.supplierId} /> },
          { id: 'date', header: t('proc.returnDate'), sortKey: 'returnDate', hideBelow: 'md', cell: (r) => <DateText value={r.returnDate} /> },
          { id: 'status', header: t('common.status'), cell: (r) => <StatusBadge status={r.status} /> },
        ]}
      />
    </div>
  );
}

const returnRoute = getRouteApi('/_authed/c/$companyId/procurement/returns/$returnId');

export function PurchaseReturnPage() {
  const { returnId } = returnRoute.useParams();
  const { companyId } = useCompany();
  const query = useCompanyQuery(['purchase-returns', returnId], (c, signal) => c.get('/purchase-returns/{returnId}', { returnId }, { signal }));
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const r = query.data;
  const actions: DocAction<Return>[] = [
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: ['procurement.return.manage'],
      confirm: { title: t('doc.postConfirm') },
      run: (c, x, ctx) => c.post('/purchase-returns/{returnId}/post', { returnId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['procurement.return.manage'],
      confirm: { title: t('doc.cancel'), destructive: true },
      run: (c, x, ctx) => c.post('/purchase-returns/{returnId}/cancel', { returnId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
    },
  ];
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/procurement/returns`} label={t('proc.returnsTitle')} />}
      title={r.number ?? t('proc.newReturn')}
      badge={<StatusBadge status={r.status} />}
      description={<EntityName source={entities.supplier} id={r.supplierId} />}
      actions={
        <>
          <AuditHistoryButton entityType="purchase_return" entityId={r.id} />
          <DocumentActions doc={r} actions={actions} />
        </>
      }
      summary={
        <DetailList
          items={[
            { label: t('proc.receipt'), value: <ReceiptLink id={r.goodsReceiptId} number={t('common.view')} /> },
            { label: t('proc.returnDate'), value: <DateText value={r.returnDate} /> },
            { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={r.warehouseId} /> },
            { label: t('proc.returnReason'), value: r.reason, wide: true },
          ]}
        />
      }
      lines={
        <LinesTable<Schemas['PurchaseReturnLine']>
          lines={r.lines ?? []}
          rowKey={(l) => l.id!}
          columns={[
            { id: 'no', header: '#', cell: (l) => l.lineNo },
            { id: 'item', header: t('doc.item'), cell: (l) => <EntityName source={entities.variant} id={l.variantId} /> },
            { id: 'location', header: t('inv.location'), hideBelow: 'sm', cell: (l) => <EntityName source={locationById} id={l.locationId} /> },
            { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
            { id: 'uom', header: t('inv.uom'), cell: (l) => <EntityName source={entities.uom} id={l.uomId} /> },
            { id: 'value', header: t('inv.value'), align: 'right', hideBelow: 'md', cell: (l) => <Money value={l.valueBase} showCurrency={false} /> },
          ]}
        />
      }
    />
  );
}
