import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Money, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable, Totals } from '@/components/document/document-layout';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, DateField, EntityField, FieldGrid, Form, TextareaField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { pricedLineColumns, pricedLineSchema, pricedLinesBody, pricedLineValues, PricedLinesEditor } from '../shared/priced-lines';

type Order = Schemas['PurchaseOrder'];
type OrderLine = Schemas['PurchaseOrderLine'];

export function PurchaseOrderLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/procurement/orders/$orderId" params={{ companyId, orderId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

export function PurchaseOrdersPage() {
  const { can, companyId } = useCompany();
  const navigate = useNavigate();
  const create = can('procurement.purchase_order.create') ? (
    <Button onClick={() => void navigate({ to: '/c/$companyId/procurement/orders/new', params: { companyId } })}>
      <Plus aria-hidden />
      {t('proc.newOrder')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.ordersTitle')} />
      <DataTable<Order>
        id="purchase-orders"
        fetchPage={(api, query, signal) => api.get('/purchase-orders', null, { query, signal })}
        rowKey={(o) => o.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.purchaseOrderStatus] },
          { kind: 'enum', key: 'billingStatus', label: t('proc.billingStatus'), values: [...enums.billingStatus] },
          { kind: 'entity', key: 'supplierId', label: t('proc.supplier'), source: entities.supplier },
          { kind: 'dateRange', field: 'orderDate', label: t('proc.orderDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (o) => <PurchaseOrderLink id={o.id} number={o.number} /> },
          { id: 'supplier', header: t('proc.supplier'), cell: (o) => <EntityName source={entities.supplier} id={o.supplierId} /> },
          { id: 'date', header: t('proc.orderDate'), sortKey: 'orderDate', hideBelow: 'sm', cell: (o) => <DateText value={o.orderDate} /> },
          { id: 'warehouse', header: t('common.warehouse'), hideBelow: 'lg', cell: (o) => <EntityName source={entities.warehouse} id={o.warehouseId} /> },
          { id: 'total', header: t('common.total'), sortKey: 'total', align: 'right', cell: (o) => <Money value={o.total} currency={o.currencyCode} /> },
          { id: 'billing', header: t('proc.billingStatus'), hideBelow: 'md', cell: (o) => <StatusBadge status={o.billingStatus} /> },
          { id: 'status', header: t('common.status'), cell: (o) => <StatusBadge status={o.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
    </div>
  );
}

const orderSchema = z.object({
  supplierId: zf.id(),
  warehouseId: zf.id(),
  departmentId: zf.optionalId(),
  orderDate: zf.date(),
  expectedDate: zf.optionalDate(),
  currencyCode: zf.optionalId(),
  paymentTermsId: zf.optionalId(),
  pricesIncludeTax: z.boolean(),
  notes: zf.optionalText(2000),
  lines: z.array(pricedLineSchema.extend({ unitPrice: zf.decimal() })).min(1, t('forms.required')),
});
type OrderValues = z.infer<typeof orderSchema>;

function orderValues(o?: Order): OrderValues {
  return {
    supplierId: o?.supplierId ?? null,
    warehouseId: o?.warehouseId ?? null,
    departmentId: o?.departmentId ?? null,
    orderDate: o?.orderDate ?? todayIso(),
    expectedDate: o?.expectedDate ?? null,
    currencyCode: o?.currencyCode ?? null,
    paymentTermsId: o?.paymentTermsId ?? null,
    pricesIncludeTax: !!o?.pricesIncludeTax,
    notes: o?.notes ?? '',
    lines: pricedLineValues(o?.lines) as OrderValues['lines'],
  };
}

/** The purchase order draft editor (header + lines); amounts are computed by the server on save. */
function OrderEditor({ order, onDone }: { order?: Order; onDone: (id: string) => void }) {
  const { api } = useCompany();
  const initial = orderValues(order);
  const form = useForm<OrderValues>({ resolver: zodResolver(orderSchema), defaultValues: initial });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    const { lines, ...header } = v;
    if (order) {
      const { lines: _l, ...before } = initial;
      await api.patch('/purchase-orders/{orderId}', { orderId: order.id! }, { body: { ...mergePatch(before, header), lines: pricedLinesBody(lines) }, ifMatch: order.version });
      notify.success(t('common.saved'));
      onDone(order.id!);
    } else {
      const created = await api.post('/purchase-orders', null, {
        body: {
          supplierId: header.supplierId!,
          warehouseId: header.warehouseId!,
          departmentId: header.departmentId ?? undefined,
          orderDate: header.orderDate ?? undefined,
          expectedDate: header.expectedDate ?? undefined,
          currencyCode: header.currencyCode ?? undefined,
          paymentTermsId: header.paymentTermsId ?? undefined,
          pricesIncludeTax: header.pricesIncludeTax || undefined,
          notes: header.notes || undefined,
          lines: pricedLinesBody(lines) as Schemas['PurchaseOrderLineRequest'][],
        },
      });
      notify.success(t('doc.created'));
      onDone(created.id!);
    }
  });
  return (
    <Form form={form} onSubmit={submit}>
      <Section>
        <FieldGrid columns={3}>
          <EntityField name="supplierId" label={t('proc.supplier')} source={entities.supplier} required />
          <EntityField name="warehouseId" label={t('common.warehouse')} source={entities.warehouse} required />
          <EntityField name="departmentId" label={t('common.department')} source={entities.department} />
          <DateField name="orderDate" label={t('proc.orderDate')} required />
          <DateField name="expectedDate" label={t('proc.expectedDate')} />
          <EntityField name="currencyCode" label={t('doc.currency')} source={entities.currency} hint={t('common.optional')} />
          <EntityField name="paymentTermsId" label={t('fields.paymentTerms')} source={entities.paymentTerms} />
        </FieldGrid>
        <div className="mt-4 space-y-4">
          <CheckboxField name="pricesIncludeTax" label={t('doc.pricesIncludeTax')} />
          <TextareaField name="notes" label={t('doc.notes')} rows={2} />
        </div>
      </Section>
      <Section title={t('common.lines')}>
        <PricedLinesEditor<OrderValues> name="lines" side="purchase" />
      </Section>
      <FormProblem {...problem} />
      <Button type="submit" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? t('common.saving') : t('doc.save')}</Button>
    </Form>
  );
}

export function NewPurchaseOrderPage() {
  const { companyId } = useCompany();
  const navigate = useNavigate();
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.newOrder')} breadcrumbs={<BackLink to={`/c/${companyId}/procurement/orders`} label={t('proc.ordersTitle')} />} />
      <OrderEditor onDone={(id) => void navigate({ to: '/c/$companyId/procurement/orders/$orderId', params: { companyId, orderId: id } })} />
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/procurement/orders/$orderId');

export function PurchaseOrderPage() {
  const { orderId } = route.useParams();
  const { companyId } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['purchase-orders', orderId], (api, signal) => api.get('/purchase-orders/{orderId}', { orderId }, { signal }));
  const [editing, setEditing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const o = query.data;
  const receivable = inStatus<Order>('APPROVED', 'PARTIALLY_RECEIVED');

  const actions: DocAction<Order>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: ['procurement.purchase_order.create'], open: () => setEditing(true) },
    { id: 'submit', label: t('doc.submit'), primary: true, when: inStatus('DRAFT'), permissions: ['procurement.purchase_order.create'], run: (api, x) => api.post('/purchase-orders/{orderId}/submit', { orderId: x.id! }, { ifMatch: x.version }), success: t('enums.SUBMITTED') },
    {
      id: 'approve',
      label: t('doc.approve'),
      primary: true,
      when: inStatus('PENDING_APPROVAL'),
      permissions: ['procurement.purchase_order.approve'],
      confirm: { title: t('doc.approve'), description: t('proc.approveHighHint') },
      run: (api, x, c) => api.post('/purchase-orders/{orderId}/approve', { orderId: x.id! }, { ifMatch: x.version, idempotencyKey: c.idempotencyKey }),
      success: t('enums.APPROVED'),
    },
    {
      id: 'reject',
      label: t('doc.reject'),
      variant: 'destructive',
      when: inStatus('PENDING_APPROVAL'),
      permissions: ['procurement.purchase_order.approve'],
      confirm: { title: t('doc.reject'), reason: 'required', reasonLabel: t('doc.rejectReason'), destructive: true },
      run: (api, x, c) => api.post('/purchase-orders/{orderId}/reject', { orderId: x.id! }, { body: { reason: c.reason }, ifMatch: x.version }),
    },
    {
      id: 'receive',
      label: t('proc.receive'),
      primary: true,
      when: receivable,
      permissions: ['procurement.receipt.create'],
      run: (api, x) => api.post('/goods-receipts', null, { body: { purchaseOrderId: x.id! } }),
      onSuccess: (r) => void navigate({ to: '/c/$companyId/procurement/receipts/$receiptId', params: { companyId, receiptId: (r as Schemas['GoodsReceipt']).id! } }),
    },
    {
      id: 'close',
      label: t('doc.close'),
      when: inStatus('APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED'),
      permissions: ['procurement.purchase_order.close'],
      confirm: { title: t('doc.close'), reason: 'optional' },
      run: (api, x, c) => api.post('/purchase-orders/{orderId}/close', { orderId: x.id! }, { body: { reason: c.reason || undefined }, ifMatch: x.version }),
    },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT', 'PENDING_APPROVAL', 'APPROVED'),
      permissions: ['procurement.purchase_order.cancel'],
      confirm: { title: t('doc.cancel'), reason: 'optional', destructive: true },
      run: (api, x, c) => api.post('/purchase-orders/{orderId}/cancel', { orderId: x.id! }, { body: { reason: c.reason || undefined }, ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['procurement.purchase_order.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (api, x) => api.delete('/purchase-orders/{orderId}', { orderId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/procurement/orders', params: { companyId } }),
    },
  ];

  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/procurement/orders`} label={t('proc.ordersTitle')} />}
      title={o.number ?? t('proc.newOrder')}
      badge={<StatusBadge status={o.status} />}
      description={<EntityName source={entities.supplier} id={o.supplierId} />}
      actions={
        <>
          <AuditHistoryButton entityType="purchase_order" entityId={o.id} />
          <DocumentActions doc={o} actions={actions} />
        </>
      }
      summary={
        editing ? undefined : (
          <DetailList
            items={[
              { label: t('proc.orderDate'), value: <DateText value={o.orderDate} /> },
              { label: t('proc.expectedDate'), value: <DateText value={o.expectedDate} /> },
              { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={o.warehouseId} /> },
              { label: t('fields.paymentTerms'), value: <EntityName source={entities.paymentTerms} id={o.paymentTermsId} /> },
              { label: t('doc.currency'), value: o.currencyCode },
              { label: t('proc.billingStatus'), value: <StatusBadge status={o.billingStatus} /> },
              o.approvedAt ? { label: t('enums.APPROVED'), value: <DateTimeText value={o.approvedAt} /> } : null,
              o.rejectionReason ? { label: t('doc.rejectReason'), value: o.rejectionReason } : null,
              o.cancelReason ? { label: t('enums.CANCELLED'), value: o.cancelReason } : null,
              o.closeReason ? { label: t('enums.CLOSED'), value: o.closeReason } : null,
              o.notes ? { label: t('doc.notes'), value: o.notes, wide: true } : null,
            ]}
          />
        )
      }
      lines={
        editing ? undefined : (
          <LinesTable<OrderLine>
            lines={o.lines ?? []}
            rowKey={(l) => l.id!}
            columns={pricedLineColumns<OrderLine>(o.currencyCode, [
              { id: 'received', header: t('doc.received'), align: 'right', hideBelow: 'md', cell: (l) => <Quantity value={l.receivedQuantityBase} /> },
              { id: 'billed', header: t('doc.billed'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.billedQuantityBase} /> },
            ])}
          />
        )
      }
      totals={
        editing ? undefined : (
          <Totals
            currency={o.currencyCode}
            rows={[
              { label: t('doc.subtotal'), value: o.subtotal },
              { label: t('doc.taxTotal'), value: o.taxTotal },
              { label: t('doc.grandTotal'), value: o.total, strong: true },
            ]}
          />
        )
      }
    >
      {editing ? (
        <div className="space-y-2">
          <Button variant="ghost" onClick={() => setEditing(false)}>{t('common.cancel')}</Button>
          <OrderEditor order={o} onDone={() => setEditing(false)} />
        </div>
      ) : null}
    </DocumentLayout>
  );
}
