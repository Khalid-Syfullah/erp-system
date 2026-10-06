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
import { DateField, EntityField, FieldGrid, TextareaField, TextField } from '@/components/form/fields';
import { DecimalInput } from '@/components/form/inputs';
import { mergePatch, zf } from '@/components/form/schema';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { t } from '@/i18n';
import { enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { SalesOrderLink } from './orders';

type Delivery = Schemas['Delivery'];

function DeliveryLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/sales/deliveries/$deliveryId" params={{ companyId, deliveryId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

const newSchema = z.object({ salesOrderId: zf.id(), deliveryDate: zf.date(), carrier: zf.optionalText(100), trackingNumber: zf.optionalText(100) });

export function DeliveriesPage() {
  const { api, can, companyId } = useCompany();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.deliveriesTitle')} />
      <DataTable<Delivery>
        id="deliveries"
        fetchPage={(c, query, signal) => c.get('/deliveries', null, { query, signal })}
        rowKey={(d) => d.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.documentStatus] },
          { kind: 'entity', key: 'customerId', label: t('sales.customer'), source: entities.customer },
          { kind: 'entity', key: 'warehouseId', label: t('common.warehouse'), source: entities.warehouse },
          { kind: 'dateRange', field: 'deliveryDate', label: t('sales.deliveryDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (d) => <DeliveryLink id={d.id} number={d.number} /> },
          { id: 'order', header: t('sales.order'), cell: (d) => <EntityName source={documents.salesOrder} id={d.salesOrderId} /> },
          { id: 'customer', header: t('sales.customer'), hideBelow: 'sm', cell: (d) => <EntityName source={entities.customer} id={d.customerId} /> },
          { id: 'date', header: t('sales.deliveryDate'), sortKey: 'deliveryDate', hideBelow: 'md', cell: (d) => <DateText value={d.deliveryDate} /> },
          { id: 'warehouse', header: t('common.warehouse'), hideBelow: 'lg', cell: (d) => <EntityName source={entities.warehouse} id={d.warehouseId} /> },
          { id: 'status', header: t('common.status'), cell: (d) => <StatusBadge status={d.status} /> },
        ]}
        toolbar={can('sales.delivery.create') ? <Button onClick={() => setCreating(true)}><Plus aria-hidden />{t('sales.newDelivery')}</Button> : null}
      />
      <FormDialog
        open={creating}
        onOpenChange={setCreating}
        title={t('sales.newDelivery')}
        description={t('sales.deliveryText')}
        schema={newSchema}
        defaults={{ salesOrderId: null, deliveryDate: todayIso(), carrier: '', trackingNumber: '' }}
        onSubmit={(v) =>
          api.post('/deliveries', null, { body: { salesOrderId: v.salesOrderId!, deliveryDate: v.deliveryDate ?? undefined, carrier: v.carrier || undefined, trackingNumber: v.trackingNumber || undefined } })
        }
        onDone={(d) => void navigate({ to: '/c/$companyId/sales/deliveries/$deliveryId', params: { companyId, deliveryId: (d as Delivery).id! } })}
      >
        <EntityField name="salesOrderId" label={t('sales.order')} source={documents.salesOrder} filters={{ 'status.in': ['CONFIRMED', 'PARTIALLY_DELIVERED'] }} required />
        <DateField name="deliveryDate" label={t('sales.deliveryDate')} required />
        <FieldGrid>
          <TextField name="carrier" label={t('sales.carrier')} />
          <TextField name="trackingNumber" label={t('sales.trackingNumber')} />
        </FieldGrid>
      </FormDialog>
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/sales/deliveries/$deliveryId');

/** A delivery: in draft, quantities and pick locations are edited in large rows (tablets); posting issues the stock. */
export function DeliveryPage() {
  const { deliveryId } = route.useParams();
  const { api, companyId, can } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['deliveries', deliveryId], (c, signal) => c.get('/deliveries/{deliveryId}', { deliveryId }, { signal }));
  const [changes, setChanges] = useState<Record<string, { quantity?: string | null; locationId?: string | null }>>({});
  const [saveError, setSaveError] = useState<unknown>(null);
  const [returning, setReturning] = useState(false);
  const [editing, setEditing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const d = query.data;
  const draft = d.status === 'DRAFT' && can('sales.delivery.create');
  const dirty = Object.keys(changes).length > 0;

  const saveLines = async () => {
    setSaveError(null);
    try {
      await api.patch('/deliveries/{deliveryId}', { deliveryId: d.id! }, {
        body: {
          lines: (d.lines ?? []).map((l) => ({
            salesOrderLineId: l.salesOrderLineId,
            quantity: changes[l.id!]?.quantity ?? l.quantity,
            uomId: l.uomId,
            locationId: changes[l.id!]?.locationId ?? l.locationId,
          })),
        },
        ifMatch: d.version,
      });
      setChanges({});
      notify.success(t('common.saved'));
      await query.refetch();
    } catch (e) {
      setSaveError(e);
    }
  };

  const actions: DocAction<Delivery>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: ['sales.delivery.create'], open: () => setEditing(true) },
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: (x) => x.status === 'DRAFT' && !dirty,
      permissions: ['sales.delivery.post'],
      confirm: { title: t('sales.postDeliveryConfirm') },
      run: (c, x, ctx) => c.post('/deliveries/{deliveryId}/post', { deliveryId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    { id: 'return', label: t('sales.newReturn'), when: inStatus('POSTED'), permissions: ['sales.return.manage'], open: () => setReturning(true) },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['sales.delivery.post'],
      confirm: { title: t('doc.cancel'), destructive: true },
      run: (c, x) => c.post('/deliveries/{deliveryId}/cancel', { deliveryId: x.id! }, { ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['sales.delivery.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/deliveries/{deliveryId}', { deliveryId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/sales/deliveries', params: { companyId } }),
    },
  ];
  const editInitial = { deliveryDate: d.deliveryDate ?? null, carrier: d.carrier ?? '', trackingNumber: d.trackingNumber ?? '', notes: d.notes ?? '' };

  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/sales/deliveries`} label={t('sales.deliveriesTitle')} />}
      title={d.number ?? t('sales.newDelivery')}
      badge={<StatusBadge status={d.status} />}
      description={<EntityName source={entities.customer} id={d.customerId} />}
      actions={
        <>
          <AuditHistoryButton entityType="delivery" entityId={d.id} />
          {draft ? (
            <Button variant="outline" onClick={() => void saveLines()} disabled={!dirty}>
              <Save aria-hidden />
              {t('common.save')}
            </Button>
          ) : null}
          <DocumentActions doc={d} actions={actions} />
        </>
      }
      summary={
        <DetailList
          items={[
            { label: t('sales.order'), value: <SalesOrderLink id={d.salesOrderId} number={t('common.view')} /> },
            { label: t('sales.deliveryDate'), value: <DateText value={d.deliveryDate} /> },
            { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={d.warehouseId} /> },
            { label: t('sales.carrier'), value: <Text value={d.carrier} /> },
            { label: t('sales.trackingNumber'), value: <Text value={d.trackingNumber} /> },
            d.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={d.postedAt} /> } : null,
            d.notes ? { label: t('doc.notes'), value: d.notes, wide: true } : null,
          ]}
        />
      }
    >
      {saveError ? <ProblemAlert error={saveError} /> : null}
      {draft ? (
        <ul className="space-y-3" aria-label={t('common.lines')}>
          {(d.lines ?? []).map((l) => (
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
                  source={locationSource(d.warehouseId)}
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
          <LinesTable<Schemas['DeliveryLine']>
            lines={d.lines ?? []}
            rowKey={(l) => l.id!}
            columns={[
              { id: 'no', header: '#', cell: (l) => l.lineNo },
              { id: 'item', header: t('doc.item'), cell: (l) => <EntityName source={entities.variant} id={l.variantId} /> },
              { id: 'location', header: t('inv.location'), hideBelow: 'sm', cell: (l) => <EntityName source={locationById} id={l.locationId} /> },
              { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
              { id: 'uom', header: t('inv.uom'), cell: (l) => <EntityName source={entities.uom} id={l.uomId} /> },
              { id: 'value', header: t('inv.value'), align: 'right', hideBelow: 'md', cell: (l) => <Money value={l.valueBase} showCurrency={false} /> },
              { id: 'returned', header: t('doc.returned'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.returnedQuantityBase} /> },
            ]}
          />
        </Section>
      )}
      <FormDialog
        open={editing}
        onOpenChange={setEditing}
        title={t('doc.edit')}
        schema={z.object({ deliveryDate: zf.date(), carrier: zf.optionalText(100), trackingNumber: zf.optionalText(100), notes: zf.optionalText(2000) })}
        defaults={editInitial}
        success={t('common.saved')}
        onSubmit={(v) => api.patch('/deliveries/{deliveryId}', { deliveryId: d.id! }, { body: mergePatch(editInitial, v), ifMatch: d.version })}
      >
        <DateField name="deliveryDate" label={t('sales.deliveryDate')} required />
        <FieldGrid>
          <TextField name="carrier" label={t('sales.carrier')} />
          <TextField name="trackingNumber" label={t('sales.trackingNumber')} />
        </FieldGrid>
        <TextareaField name="notes" label={t('doc.notes')} rows={2} />
      </FormDialog>
      {returning ? <SalesReturnDialog delivery={d} onClose={() => setReturning(false)} /> : null}
    </DocumentLayout>
  );
}

const returnSchema = z.object({
  returnDate: zf.date(),
  reason: zf.text(500),
  lines: z.array(z.object({ deliveryLineId: z.string(), quantity: zf.optionalDecimal() })),
});

function SalesReturnDialog({ delivery, onClose }: { delivery: Delivery; onClose: () => void }) {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const lines = delivery.lines ?? [];
  return (
    <FormDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('sales.newReturn')}
      size="lg"
      schema={returnSchema}
      defaults={{ returnDate: todayIso(), reason: '', lines: lines.map((l) => ({ deliveryLineId: l.id!, quantity: null })) }}
      onSubmit={(v) =>
        api.post('/sales-returns', null, {
          body: {
            deliveryId: delivery.id!,
            returnDate: v.returnDate ?? undefined,
            reason: v.reason,
            lines: v.lines.filter((l) => l.quantity).map((l) => ({ deliveryLineId: l.deliveryLineId, quantity: l.quantity! })),
          },
        })
      }
      onDone={(r) => void navigate({ to: '/c/$companyId/sales/returns/$returnId', params: { companyId, returnId: (r as Schemas['SalesReturn']).id! } })}
    >
      <DateField name="returnDate" label={t('sales.returnDate')} required />
      <TextareaField name="reason" label={t('sales.returnReason')} required rows={2} />
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

// --- Sales returns ----------------------------------------------------------------------------

type Return = Schemas['SalesReturn'];

export function SalesReturnsPage() {
  const { companyId } = useCompany();
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.returnsTitle')} />
      <DataTable<Return>
        id="sales-returns"
        fetchPage={(c, query, signal) => c.get('/sales-returns', null, { query, signal })}
        rowKey={(r) => r.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.salesReturnStatus] },
          { kind: 'entity', key: 'customerId', label: t('sales.customer'), source: entities.customer },
        ]}
        columns={[
          {
            id: 'number',
            header: t('common.number'),
            cell: (r) => (
              <Link to="/c/$companyId/sales/returns/$returnId" params={{ companyId, returnId: r.id! }} data-row-link className="font-medium text-primary hover:underline">
                {r.number ?? t('doc.draft')}
              </Link>
            ),
          },
          { id: 'delivery', header: t('sales.delivery'), cell: (r) => <EntityName source={documents.delivery} id={r.deliveryId} /> },
          { id: 'customer', header: t('sales.customer'), hideBelow: 'sm', cell: (r) => <EntityName source={entities.customer} id={r.customerId} /> },
          { id: 'date', header: t('sales.returnDate'), sortKey: 'returnDate', hideBelow: 'md', cell: (r) => <DateText value={r.returnDate} /> },
          { id: 'status', header: t('common.status'), cell: (r) => <StatusBadge status={r.status} /> },
        ]}
      />
    </div>
  );
}

const returnRoute = getRouteApi('/_authed/c/$companyId/sales/returns/$returnId');

export function SalesReturnPage() {
  const { returnId } = returnRoute.useParams();
  const { companyId } = useCompany();
  const query = useCompanyQuery(['sales-returns', returnId], (c, signal) => c.get('/sales-returns/{returnId}', { returnId }, { signal }));
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const r = query.data;
  const actions: DocAction<Return>[] = [
    {
      id: 'receive',
      label: t('sales.receiveReturn'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: ['sales.return.manage'],
      confirm: { title: t('sales.receiveReturnConfirm') },
      run: (c, x, ctx) => c.post('/sales-returns/{returnId}/receive', { returnId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.RECEIVED'),
    },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['sales.return.manage'],
      confirm: { title: t('doc.cancel'), destructive: true },
      run: (c, x) => c.post('/sales-returns/{returnId}/cancel', { returnId: x.id! }, { ifMatch: x.version }),
    },
  ];
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/sales/returns`} label={t('sales.returnsTitle')} />}
      title={r.number ?? t('sales.newReturn')}
      badge={<StatusBadge status={r.status} />}
      description={<EntityName source={entities.customer} id={r.customerId} />}
      actions={
        <>
          <AuditHistoryButton entityType="sales_return" entityId={r.id} />
          <DocumentActions doc={r} actions={actions} />
        </>
      }
      summary={
        <DetailList
          items={[
            { label: t('sales.delivery'), value: <DeliveryLink id={r.deliveryId} number={t('common.view')} /> },
            { label: t('sales.order'), value: <SalesOrderLink id={r.salesOrderId} number={t('common.view')} /> },
            { label: t('sales.returnDate'), value: <DateText value={r.returnDate} /> },
            { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={r.warehouseId} /> },
            { label: t('sales.returnReason'), value: r.reason, wide: true },
          ]}
        />
      }
      lines={
        <LinesTable<Schemas['SalesReturnLine']>
          lines={r.lines ?? []}
          rowKey={(l) => l.id!}
          columns={[
            { id: 'no', header: '#', cell: (l) => l.lineNo },
            { id: 'item', header: t('doc.item'), cell: (l) => <EntityName source={entities.variant} id={l.variantId} /> },
            { id: 'location', header: t('inv.location'), hideBelow: 'sm', cell: (l) => <EntityName source={locationById} id={l.locationId} /> },
            { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
            { id: 'uom', header: t('inv.uom'), cell: (l) => <EntityName source={entities.uom} id={l.uomId} /> },
            { id: 'credited', header: t('doc.credited'), align: 'right', hideBelow: 'md', cell: (l) => <Quantity value={l.creditedQuantityBase} /> },
          ]}
        />
      }
    />
  );
}
