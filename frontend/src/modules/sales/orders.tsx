import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Calculator, Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm, useWatch, type UseFormReturn } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { hasCode } from '@/api/errors';
import { companyKey, newIdempotencyKey, useCompanyQuery } from '@/api/hooks';
import { useQueryClient } from '@tanstack/react-query';
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
import { ErrorState, LoadingState, ProblemAlert } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, EntityField, FieldGrid, Form, SelectField, TextareaField, TextField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { pricedLineColumns, pricedLineSchema, pricedLinesBody, pricedLineValues, PricedLinesEditor } from '../shared/priced-lines';

type Order = Schemas['SalesOrder'];
type Quotation = Schemas['Quotation'];
type Kind = 'order' | 'quotation';

export function SalesOrderLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/sales/orders/$orderId" params={{ companyId, orderId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

function QuotationLink({ id, number }: { id: string; number?: string | null }) {
  const { companyId } = useCompany();
  return (
    <Link to="/c/$companyId/sales/quotations/$quotationId" params={{ companyId, quotationId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

export function SalesOrdersPage() {
  const { can, companyId } = useCompany();
  const navigate = useNavigate();
  const create = can('sales.order.create') ? (
    <Button onClick={() => void navigate({ to: '/c/$companyId/sales/orders/new', params: { companyId } })}>
      <Plus aria-hidden />
      {t('sales.newOrder')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.ordersTitle')} />
      <DataTable<Order>
        id="sales-orders"
        fetchPage={(api, query, signal) => api.get('/sales-orders', null, { query, signal })}
        rowKey={(o) => o.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.salesOrderStatus] },
          { kind: 'enum', key: 'invoiceStatus', label: t('sales.invoiceStatus'), values: [...enums.invoiceStatus] },
          { kind: 'entity', key: 'customerId', label: t('sales.customer'), source: entities.customer },
          { kind: 'dateRange', field: 'orderDate', label: t('sales.orderDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (o) => <SalesOrderLink id={o.id} number={o.number} /> },
          { id: 'customer', header: t('sales.customer'), cell: (o) => <EntityName source={entities.customer} id={o.customerId} /> },
          { id: 'date', header: t('sales.orderDate'), sortKey: 'orderDate', hideBelow: 'sm', cell: (o) => <DateText value={o.orderDate} /> },
          { id: 'ref', header: t('sales.customerReference'), hideBelow: 'lg', cell: (o) => <Text value={o.customerReference} /> },
          { id: 'total', header: t('common.total'), sortKey: 'total', align: 'right', cell: (o) => <Money value={o.total} currency={o.currencyCode} /> },
          { id: 'invoicing', header: t('sales.invoiceStatus'), hideBelow: 'md', cell: (o) => <StatusBadge status={o.invoiceStatus} /> },
          { id: 'status', header: t('common.status'), cell: (o) => <StatusBadge status={o.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
    </div>
  );
}

export function QuotationsPage() {
  const { can, companyId } = useCompany();
  const navigate = useNavigate();
  const create = can('sales.quotation.manage') ? (
    <Button onClick={() => void navigate({ to: '/c/$companyId/sales/quotations/new', params: { companyId } })}>
      <Plus aria-hidden />
      {t('sales.newQuotation')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.quotationsTitle')} />
      <DataTable<Quotation>
        id="quotations"
        fetchPage={(api, query, signal) => api.get('/quotations', null, { query, signal })}
        rowKey={(q) => q.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.quotationStatus] },
          { kind: 'entity', key: 'customerId', label: t('sales.customer'), source: entities.customer },
          { kind: 'dateRange', field: 'quotationDate', label: t('sales.quotationDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (q) => <QuotationLink id={q.id!} number={q.number} /> },
          { id: 'customer', header: t('sales.customer'), cell: (q) => <EntityName source={entities.customer} id={q.customerId} /> },
          { id: 'date', header: t('sales.quotationDate'), sortKey: 'quotationDate', hideBelow: 'sm', cell: (q) => <DateText value={q.quotationDate} /> },
          { id: 'valid', header: t('sales.validUntil'), sortKey: 'validUntil', hideBelow: 'md', cell: (q) => <DateText value={q.validUntil} /> },
          { id: 'total', header: t('common.total'), sortKey: 'total', align: 'right', cell: (q) => <Money value={q.total} currency={q.currencyCode} /> },
          { id: 'status', header: t('common.status'), cell: (q) => <StatusBadge status={q.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
    </div>
  );
}

// --- Editor (quotation and order) -------------------------------------------------------------

const editorSchema = z.object({
  customerId: zf.id(),
  warehouseId: zf.id(),
  date: zf.optionalDate(),
  secondDate: zf.optionalDate(),
  customerReference: zf.optionalText(100),
  currencyCode: zf.optionalId(),
  priceListId: zf.optionalId(),
  paymentTermsId: zf.optionalId(),
  invoicePolicy: zf.optionalId(),
  notes: zf.optionalText(2000),
  lines: z.array(pricedLineSchema).min(1, t('forms.required')),
});
type EditorValues = z.infer<typeof editorSchema>;

function editorValues(kind: Kind, doc?: Order | Quotation): EditorValues {
  const order = kind === 'order' ? (doc as Order | undefined) : undefined;
  const quote = kind === 'quotation' ? (doc as Quotation | undefined) : undefined;
  return {
    customerId: doc?.customerId ?? null,
    warehouseId: doc?.warehouseId ?? null,
    date: order?.orderDate ?? quote?.quotationDate ?? todayIso(),
    secondDate: order?.requestedDate ?? quote?.validUntil ?? null,
    customerReference: order?.customerReference ?? '',
    currencyCode: doc?.currencyCode ?? null,
    priceListId: doc?.priceListId ?? null,
    paymentTermsId: doc?.paymentTermsId ?? null,
    invoicePolicy: order?.invoicePolicy ?? null,
    notes: doc?.notes ?? '',
    lines: pricedLineValues(doc?.lines),
  };
}

function headerBody(kind: Kind, v: EditorValues) {
  const common = {
    customerId: v.customerId,
    warehouseId: v.warehouseId,
    currencyCode: v.currencyCode,
    priceListId: v.priceListId,
    paymentTermsId: v.paymentTermsId,
    notes: v.notes,
  };
  return kind === 'order'
    ? { ...common, orderDate: v.date, requestedDate: v.secondDate, customerReference: v.customerReference }
    : { ...common, quotationDate: v.date, validUntil: v.secondDate };
}

/** Server-computed prices before saving (POST /pricing/quote, nothing is persisted). */
function PricePreview({ form }: { form: UseFormReturn<EditorValues> }) {
  const { api, can } = useCompany();
  const [quote, setQuote] = useState<Schemas['PriceQuote'] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const customerId = useWatch({ control: form.control, name: 'customerId' });
  if (!can('sales.order.create')) return null;
  const run = async () => {
    const v = form.getValues();
    setError(null);
    try {
      setQuote(
        await api.post('/pricing/quote', null, {
          body: {
            customerId: v.customerId!,
            currencyCode: v.currencyCode ?? undefined,
            date: v.date ?? undefined,
            priceListId: v.priceListId ?? undefined,
            lines: v.lines.filter((l) => l.variantId && l.uomId && l.quantity).map((l) => ({ variantId: l.variantId!, quantity: l.quantity!, uomId: l.uomId!, taxCodeId: l.taxCodeId ?? undefined })),
          },
        }),
      );
    } catch (e) {
      setError(e);
    }
  };
  return (
    <>
      <Button type="button" variant="outline" onClick={() => void run()} disabled={!customerId}>
        <Calculator aria-hidden />
        {t('sales.preview')}
      </Button>
      <Modal open={quote !== null || error !== null} onOpenChange={(open) => !open && (setQuote(null), setError(null))} title={t('sales.previewTitle')} size="lg">
        <ProblemAlert error={error} />
        {quote ? (
          <div className="space-y-3">
            <LinesTable<Schemas['QuotedLine']>
              lines={quote.lines ?? []}
              rowKey={(l) => `${l.variantId}-${l.quantity}`}
              columns={[
                { id: 'item', header: t('doc.item'), cell: (l) => <EntityName source={entities.variant} id={l.variantId} /> },
                { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
                { id: 'list', header: t('sales.listPrice'), align: 'right', cell: (l) => <Money value={l.listPrice} currency={quote.currencyCode} showCurrency={false} /> },
                { id: 'price', header: t('doc.unitPrice'), align: 'right', cell: (l) => <Money value={l.unitPrice} currency={quote.currencyCode} showCurrency={false} /> },
                { id: 'total', header: t('doc.lineTotal'), align: 'right', cell: (l) => <Money value={l.totalAmount} currency={quote.currencyCode} showCurrency={false} /> },
              ]}
            />
            <Totals currency={quote.currencyCode} rows={[{ label: t('doc.subtotal'), value: quote.subtotal }, { label: t('doc.taxTotal'), value: quote.taxTotal }, { label: t('doc.grandTotal'), value: quote.total, strong: true }]} />
          </div>
        ) : null}
      </Modal>
    </>
  );
}

function SalesEditor({ kind, doc, onDone }: { kind: Kind; doc?: Order | Quotation; onDone: (id: string) => void }) {
  const { api } = useCompany();
  const initial = editorValues(kind, doc);
  const form = useForm<EditorValues>({ resolver: zodResolver(editorSchema), defaultValues: initial });
  const { submit, ...problem } = useSubmit(
    form,
    async (v) => {
      const lines = pricedLinesBody(v.lines);
      const header = headerBody(kind, v);
      if (doc) {
        const before = headerBody(kind, initial);
        const body = { ...mergePatch(before, header), lines };
        if (kind === 'order') await api.patch('/sales-orders/{orderId}', { orderId: doc.id! }, { body, ifMatch: doc.version });
        else await api.patch('/quotations/{quotationId}', { quotationId: doc.id! }, { body, ifMatch: doc.version });
        notify.success(t('common.saved'));
        onDone(doc.id!);
        return;
      }
      const withPolicy = kind === 'order' ? { ...header, invoicePolicy: v.invoicePolicy } : header;
      const clean = Object.fromEntries(Object.entries(withPolicy).filter(([, value]) => value !== null && value !== ''));
      const created =
        kind === 'order'
          ? await api.post('/sales-orders', null, { body: { ...(clean as unknown as Schemas['SalesOrderOrderRequest']), lines: lines as Schemas['PricedLine'][] } })
          : await api.post('/quotations', null, { body: { ...(clean as unknown as Schemas['QuotationRequest']), lines: lines as Schemas['PricedLine'][] } });
      notify.success(t('doc.created'));
      onDone(created.id!);
    },
    { fieldMap: { orderDate: 'date', quotationDate: 'date', requestedDate: 'secondDate', validUntil: 'secondDate' } },
  );
  return (
    <Form form={form} onSubmit={submit}>
      <Section>
        <FieldGrid columns={3}>
          <EntityField name="customerId" label={t('sales.customer')} source={entities.customer} required />
          <EntityField name="warehouseId" label={t('common.warehouse')} source={entities.warehouse} required />
          <DateField name="date" label={kind === 'order' ? t('sales.orderDate') : t('sales.quotationDate')} />
          <DateField name="secondDate" label={kind === 'order' ? t('sales.requestedDate') : t('sales.validUntil')} />
          {kind === 'order' ? <TextField name="customerReference" label={t('sales.customerReference')} /> : null}
          <EntityField name="priceListId" label={t('sales.priceList')} source={entities.priceList} />
          <EntityField name="currencyCode" label={t('doc.currency')} source={entities.currency} />
          <EntityField name="paymentTermsId" label={t('fields.paymentTerms')} source={entities.paymentTerms} />
          {kind === 'order' && !doc ? <SelectField name="invoicePolicy" label={t('sales.invoicePolicy')} options={enumOptions(enums.invoicePolicy)} allowEmpty /> : null}
        </FieldGrid>
        <div className="mt-4">
          <TextareaField name="notes" label={t('doc.notes')} rows={2} />
        </div>
      </Section>
      <Section title={t('common.lines')}>
        <PricedLinesEditor<EditorValues> name="lines" side="sales" priceHint={t('sales.priceHint')} />
      </Section>
      <FormProblem {...problem} />
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={form.formState.isSubmitting}>{t('doc.save')}</Button>
        <PricePreview form={form} />
      </div>
    </Form>
  );
}

export function NewSalesOrderPage() {
  const { companyId } = useCompany();
  const navigate = useNavigate();
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.newOrder')} breadcrumbs={<BackLink to={`/c/${companyId}/sales/orders`} label={t('sales.ordersTitle')} />} />
      <SalesEditor kind="order" onDone={(id) => void navigate({ to: '/c/$companyId/sales/orders/$orderId', params: { companyId, orderId: id } })} />
    </div>
  );
}

export function NewQuotationPage() {
  const { companyId } = useCompany();
  const navigate = useNavigate();
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.newQuotation')} breadcrumbs={<BackLink to={`/c/${companyId}/sales/quotations`} label={t('sales.quotationsTitle')} />} />
      <SalesEditor kind="quotation" onDone={(id) => void navigate({ to: '/c/$companyId/sales/quotations/$quotationId', params: { companyId, quotationId: id } })} />
    </div>
  );
}

// --- Quotation detail -------------------------------------------------------------------------

const quotationRoute = getRouteApi('/_authed/c/$companyId/sales/quotations/$quotationId');

export function QuotationPage() {
  const { quotationId } = quotationRoute.useParams();
  const { companyId } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['quotations', quotationId], (c, signal) => c.get('/quotations/{quotationId}', { quotationId }, { signal }));
  const [editing, setEditing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const q = query.data;
  const manage = ['sales.quotation.manage'];
  const actions: DocAction<Quotation>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: manage, open: () => setEditing(true) },
    { id: 'send', label: t('sales.send'), primary: true, when: inStatus('DRAFT'), permissions: manage, run: (c, x) => c.post('/quotations/{quotationId}/send', { quotationId: x.id! }, { ifMatch: x.version }), success: t('enums.SENT') },
    {
      id: 'accept',
      label: t('sales.accept'),
      primary: true,
      when: inStatus('SENT'),
      permissions: [...manage, 'sales.order.create'],
      run: (c, x, ctx) => c.post('/quotations/{quotationId}/accept', { quotationId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      onSuccess: (order) => void navigate({ to: '/c/$companyId/sales/orders/$orderId', params: { companyId, orderId: (order as Order).id! } }),
    },
    {
      id: 'reject',
      label: t('doc.reject'),
      variant: 'destructive',
      when: inStatus('SENT'),
      permissions: manage,
      confirm: { title: t('doc.reject'), reason: 'required', destructive: true },
      run: (c, x, ctx) => c.post('/quotations/{quotationId}/reject', { quotationId: x.id! }, { body: { reason: ctx.reason }, ifMatch: x.version }),
    },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT', 'SENT'),
      permissions: manage,
      confirm: { title: t('doc.cancel'), reason: 'optional', destructive: true },
      run: (c, x, ctx) => c.post('/quotations/{quotationId}/cancel', { quotationId: x.id! }, { body: { reason: ctx.reason || undefined }, ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: manage,
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/quotations/{quotationId}', { quotationId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/sales/quotations', params: { companyId } }),
    },
  ];
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/sales/quotations`} label={t('sales.quotationsTitle')} />}
      title={q.number ?? t('sales.newQuotation')}
      badge={<StatusBadge status={q.status} />}
      description={<EntityName source={entities.customer} id={q.customerId} />}
      actions={
        <>
          <AuditHistoryButton entityType="quotation" entityId={q.id} />
          <DocumentActions doc={q} actions={actions} />
        </>
      }
      summary={
        editing ? undefined : (
          <DetailList
            items={[
              { label: t('sales.quotationDate'), value: <DateText value={q.quotationDate} /> },
              { label: t('sales.validUntil'), value: <DateText value={q.validUntil} /> },
              { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={q.warehouseId} /> },
              { label: t('sales.priceList'), value: <EntityName source={entities.priceList} id={q.priceListId} /> },
              { label: t('doc.currency'), value: q.currencyCode },
              q.salesOrderId ? { label: t('sales.order'), value: <SalesOrderLink id={q.salesOrderId} number={t('common.view')} /> } : null,
              q.rejectionReason ? { label: t('doc.rejectReason'), value: q.rejectionReason } : null,
              q.notes ? { label: t('doc.notes'), value: q.notes, wide: true } : null,
            ]}
          />
        )
      }
      lines={editing ? undefined : <LinesTable<Schemas['Line']> lines={q.lines ?? []} rowKey={(l) => l.id!} columns={pricedLineColumns(q.currencyCode)} />}
      totals={editing ? undefined : <Totals currency={q.currencyCode} rows={[{ label: t('doc.subtotal'), value: q.subtotal }, { label: t('doc.taxTotal'), value: q.taxTotal }, { label: t('doc.grandTotal'), value: q.total, strong: true }]} />}
    >
      {editing ? (
        <div className="space-y-2">
          <Button variant="ghost" onClick={() => setEditing(false)}>{t('common.cancel')}</Button>
          <SalesEditor kind="quotation" doc={q} onDone={() => setEditing(false)} />
        </div>
      ) : null}
    </DocumentLayout>
  );
}

// --- Sales order detail -----------------------------------------------------------------------

const orderRoute = getRouteApi('/_authed/c/$companyId/sales/orders/$orderId');

export function SalesOrderPage() {
  const { orderId } = orderRoute.useParams();
  const { companyId, can } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['sales-orders', orderId], (c, signal) => c.get('/sales-orders/{orderId}', { orderId }, { signal }));
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const o = query.data;
  const actions: DocAction<Order>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: ['sales.order.create'], open: () => setEditing(true) },
    { id: 'confirm', label: t('sales.confirm'), primary: true, when: inStatus('DRAFT'), permissions: ['sales.order.confirm'], open: () => setConfirming(true) },
    {
      id: 'deliver',
      label: t('sales.deliver'),
      primary: true,
      when: inStatus('CONFIRMED', 'PARTIALLY_DELIVERED'),
      permissions: ['sales.delivery.create'],
      run: (c, x) => c.post('/deliveries', null, { body: { salesOrderId: x.id! } }),
      onSuccess: (d) => void navigate({ to: '/c/$companyId/sales/deliveries/$deliveryId', params: { companyId, deliveryId: (d as Schemas['Delivery']).id! } }),
    },
    {
      id: 'invoice',
      label: t('sales.createInvoice'),
      when: (x) => ['CONFIRMED', 'PARTIALLY_DELIVERED', 'DELIVERED', 'CLOSED'].includes(x.status ?? '') && x.invoiceStatus !== 'INVOICED',
      permissions: ['sales.invoice.create'],
      run: (c, x) => c.post('/invoices/from-order', null, { body: { salesOrderId: x.id! } }),
      onSuccess: (i) => void navigate({ to: '/c/$companyId/sales/invoices/$invoiceId', params: { companyId, invoiceId: (i as Schemas['Invoice']).id! } }),
    },
    { id: 'reserve', label: t('sales.reserve'), when: inStatus('CONFIRMED', 'PARTIALLY_DELIVERED'), permissions: ['sales.order.confirm'], run: (c, x) => c.post('/sales-orders/{orderId}/reserve', { orderId: x.id! }, { ifMatch: x.version }), success: t('common.saved') },
    {
      id: 'close',
      label: t('doc.close'),
      when: inStatus('CONFIRMED', 'PARTIALLY_DELIVERED', 'DELIVERED'),
      permissions: ['sales.order.close'],
      confirm: { title: t('doc.close'), reason: 'optional' },
      run: (c, x, ctx) => c.post('/sales-orders/{orderId}/close', { orderId: x.id! }, { body: { reason: ctx.reason || undefined }, ifMatch: x.version }),
    },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT', 'CONFIRMED'),
      permissions: ['sales.order.cancel'],
      confirm: { title: t('doc.cancel'), reason: 'optional', destructive: true },
      run: (c, x, ctx) => c.post('/sales-orders/{orderId}/cancel', { orderId: x.id! }, { body: { reason: ctx.reason || undefined }, ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['sales.order.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/sales-orders/{orderId}', { orderId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/sales/orders', params: { companyId } }),
    },
  ];
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/sales/orders`} label={t('sales.ordersTitle')} />}
      title={o.number ?? t('sales.newOrder')}
      badge={
        <>
          <StatusBadge status={o.status} />
          {o.status !== 'DRAFT' ? <StatusBadge status={o.invoiceStatus} /> : null}
        </>
      }
      description={<EntityName source={entities.customer} id={o.customerId} />}
      actions={
        <>
          <AuditHistoryButton entityType="sales_order" entityId={o.id} />
          <DocumentActions doc={o} actions={actions} />
        </>
      }
      summary={
        editing ? undefined : (
          <DetailList
            items={[
              { label: t('sales.orderDate'), value: <DateText value={o.orderDate} /> },
              { label: t('sales.requestedDate'), value: <DateText value={o.requestedDate} /> },
              { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={o.warehouseId} /> },
              { label: t('sales.customerReference'), value: <Text value={o.customerReference} /> },
              { label: t('sales.priceList'), value: <EntityName source={entities.priceList} id={o.priceListId} /> },
              { label: t('sales.invoicePolicy'), value: enumLabel(o.invoicePolicy) },
              { label: t('doc.currency'), value: o.currencyCode },
              o.creditCheckResult ? { label: t('sales.creditCheck'), value: <StatusBadge status={o.creditCheckResult} /> } : null,
              o.creditOverrideReason ? { label: t('sales.overrideReason'), value: o.creditOverrideReason } : null,
              o.confirmedAt ? { label: t('enums.CONFIRMED'), value: <DateTimeText value={o.confirmedAt} /> } : null,
              o.notes ? { label: t('doc.notes'), value: o.notes, wide: true } : null,
            ]}
          />
        )
      }
      lines={
        editing ? undefined : (
          <LinesTable<Schemas['SalesOrderLine']>
            lines={o.lines ?? []}
            rowKey={(l) => l.id!}
            columns={pricedLineColumns<Schemas['SalesOrderLine']>(o.currencyCode, [
              { id: 'reserved', header: t('doc.reserved'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.reservedQuantityBase} /> },
              { id: 'delivered', header: t('doc.delivered'), align: 'right', hideBelow: 'md', cell: (l) => <Quantity value={l.deliveredQuantityBase} /> },
              { id: 'invoiced', header: t('doc.invoiced'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.invoicedQuantityBase} /> },
            ])}
          />
        )
      }
      totals={editing ? undefined : <Totals currency={o.currencyCode} rows={[{ label: t('doc.subtotal'), value: o.subtotal }, { label: t('doc.taxTotal'), value: o.taxTotal }, { label: t('doc.grandTotal'), value: o.total, strong: true }]} />}
    >
      {editing ? (
        <div className="space-y-2">
          <Button variant="ghost" onClick={() => setEditing(false)}>{t('common.cancel')}</Button>
          <SalesEditor kind="order" doc={o} onDone={() => setEditing(false)} />
        </div>
      ) : null}
      {confirming ? <ConfirmOrderDialog order={o} canOverride={can('sales.order.override_credit')} onClose={() => setConfirming(false)} onDone={() => void query.refetch()} /> : null}
    </DocumentLayout>
  );
}

/**
 * Confirming runs the credit check (SAL-2). A blocked check (422 CREDIT_LIMIT_EXCEEDED or
 * PARTNER_ON_HOLD) can be overridden with a reason by users holding sales.order.override_credit.
 */
function ConfirmOrderDialog({ order, canOverride, onClose, onDone }: { order: Order; canOverride: boolean; onClose: () => void; onDone: () => void }) {
  const { api, companyId } = useCompany();
  const queryClient = useQueryClient();
  const check = useCompanyQuery(['sales-orders', order.id, 'credit-check'], (c, signal) => c.get('/sales-orders/{orderId}/credit-check', { orderId: order.id! }, { signal }));
  const [key] = useState(newIdempotencyKey);
  const [error, setError] = useState<unknown>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const blocked = hasCode(error, 'CREDIT_LIMIT_EXCEEDED', 'PARTNER_ON_HOLD');
  const confirm = async (override: boolean) => {
    setBusy(true);
    setError(null);
    try {
      await api.post('/sales-orders/{orderId}/confirm', { orderId: order.id! }, {
        body: override ? { overrideCredit: { reason } } : {},
        ifMatch: order.version,
        idempotencyKey: override ? `${key}-override` : key,
      });
      await queryClient.invalidateQueries({ queryKey: companyKey(companyId) });
      notify.success(t('enums.CONFIRMED'));
      onDone();
      onClose();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const c = check.data;
  return (
    <Modal
      open
      onOpenChange={(open) => !open && !busy && onClose()}
      title={t('sales.confirm')}
      description={t('sales.confirmText')}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={busy}>{t('common.cancel')}</Button>
          {blocked && canOverride ? (
            <Button variant="destructive" onClick={() => void confirm(true)} disabled={busy || reason.trim() === ''}>{t('sales.overrideCredit')}</Button>
          ) : (
            <Button onClick={() => void confirm(false)} disabled={busy}>{t('sales.confirm')}</Button>
          )}
        </>
      }
    >
      {c ? (
        <DetailList
          columns={2}
          items={[
            { label: t('sales.creditCheck'), value: enumLabel(c.mode) },
            { label: t('sales.outcome'), value: <StatusBadge status={c.outcome} /> },
            { label: t('sales.creditLimit'), value: <Money value={c.creditLimit} showCurrency={false} /> },
            { label: t('sales.openReceivables'), value: <Money value={c.openReceivablesBase} showCurrency={false} /> },
            { label: t('sales.openOrders'), value: <Money value={c.openOrdersBase} showCurrency={false} /> },
            { label: t('sales.orderTotalBase'), value: <Money value={c.orderTotalBase} showCurrency={false} /> },
            { label: t('sales.exposure'), value: <Money value={c.exposureBase} showCurrency={false} /> },
            c.onHold ? { label: t('fields.onHold'), value: t('common.yes') } : null,
          ]}
        />
      ) : check.isLoading ? (
        <LoadingState />
      ) : null}
      <ProblemAlert error={error} />
      {blocked && canOverride ? (
        <div className="space-y-1.5">
          <Label htmlFor="override-reason">{t('sales.overrideReason')}</Label>
          <Textarea id="override-reason" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
        </div>
      ) : null}
    </Modal>
  );
}
