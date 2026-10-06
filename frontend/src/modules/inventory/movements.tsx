import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Pencil, Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import type { CompanyApi, Schemas } from '@/api/client';
import { newIdempotencyKey, useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Money, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities, locationById, locationSource } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable } from '@/components/document/document-layout';
import { LineDecimal, LineEntity, LinesEditor, type LineColumn } from '@/components/document/lines-editor';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, DateField, EntityField, FieldGrid, Form, SelectField, TextareaField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { FormDialog } from '@/components/overlay/form-dialog';
import { PageHeader, Section } from '@/components/common/page';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';

type Movement = Schemas['Movement'];
type MovementLine = Schemas['MovementLine'];

export function MovementLink({ id, number }: { id: string; number?: string | null }) {
  const { companyId } = useCompany();
  return (
    <Link to="/c/$companyId/inventory/movements/$movementId" params={{ companyId, movementId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('enums.DRAFT')}
    </Link>
  );
}

export function MovementsPage() {
  const { can, companyId } = useCompany();
  const navigate = useNavigate();
  const create = can('inventory.movement.create') ? (
    <Button onClick={() => void navigate({ to: '/c/$companyId/inventory/movements/new', params: { companyId } })}>
      <Plus aria-hidden />
      {t('inv.newMovement')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('inv.movementsTitle')} />
      <DataTable<Movement>
        id="movements"
        fetchPage={(api, query, signal) => api.get('/stock-movements', null, { query, signal })}
        rowKey={(m) => m.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'movementType', label: t('inv.movementType'), values: [...enums.movementType] },
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.documentStatus] },
          { kind: 'entity', key: 'warehouseId', label: t('common.warehouse'), source: entities.warehouse },
          { kind: 'dateRange', field: 'movementDate', label: t('inv.movementDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (m) => <MovementLink id={m.id!} number={m.number} /> },
          { id: 'type', header: t('inv.movementType'), cell: (m) => enumLabel(m.movementType) },
          { id: 'date', header: t('inv.movementDate'), sortKey: 'movementDate', cell: (m) => <DateText value={m.movementDate} /> },
          { id: 'warehouse', header: t('common.warehouse'), hideBelow: 'sm', cell: (m) => <EntityName source={entities.warehouse} id={m.warehouseId} /> },
          { id: 'dest', header: t('inv.destWarehouse'), hideBelow: 'lg', cell: (m) => <EntityName source={entities.warehouse} id={m.destWarehouseId} fallback="" /> },
          { id: 'source', header: t('inv.source'), hideBelow: 'md', cell: (m) => <Text value={m.source?.number} /> },
          { id: 'lines', header: t('common.lines'), align: 'right', hideBelow: 'md', cell: (m) => m.lines?.length ?? 0 },
          { id: 'status', header: t('common.status'), cell: (m) => <StatusBadge status={m.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
    </div>
  );
}

// --- Editor -----------------------------------------------------------------------------------

const lineSchema = z.object({
  variantId: zf.id(),
  fromLocationId: zf.optionalId(),
  toLocationId: zf.optionalId(),
  quantity: zf.decimal(),
  uomId: zf.id(),
  unitCostBase: zf.optionalDecimal(),
});
const movementSchema = z.object({
  movementType: zf.id(),
  movementDate: zf.date(),
  warehouseId: zf.id(),
  destWarehouseId: zf.optionalId(),
  reasonCodeId: zf.optionalId(),
  notes: zf.optionalText(1000),
  postImmediately: z.boolean(),
  lines: z.array(lineSchema).min(1, t('forms.required')),
});
type MovementValues = z.infer<typeof movementSchema>;

const emptyLine = (): MovementValues['lines'][number] => ({ variantId: null, fromLocationId: null, toLocationId: null, quantity: null, uomId: null, unitCostBase: null });

function movementValues(m?: Movement): MovementValues {
  return {
    movementType: m?.movementType ?? 'TRANSFER',
    movementDate: m?.movementDate ?? todayIso(),
    warehouseId: m?.warehouseId ?? null,
    destWarehouseId: m?.destWarehouseId ?? null,
    reasonCodeId: m?.reasonCodeId ?? null,
    notes: m?.notes ?? '',
    postImmediately: false,
    lines: m?.lines?.length
      ? m.lines.map((l) => ({
          variantId: l.variantId ?? null,
          fromLocationId: l.fromLocationId ?? null,
          toLocationId: l.toLocationId ?? null,
          quantity: l.quantity ?? null,
          uomId: l.uomId ?? null,
          unitCostBase: l.unitCostBase ?? null,
        }))
      : [emptyLine()],
  };
}

function linesBody(lines: MovementValues['lines']) {
  return lines.map((l) => ({
    variantId: l.variantId!,
    fromLocationId: l.fromLocationId ?? undefined,
    toLocationId: l.toLocationId ?? undefined,
    quantity: l.quantity!,
    uomId: l.uomId!,
    unitCostBase: l.unitCostBase || undefined,
  }));
}

/** Sets a line's unit to the product's base unit when an item is chosen. */
async function baseUomOf(api: CompanyApi, variant: Schemas['Variant'] | undefined): Promise<string | null> {
  if (!variant?.productId) return null;
  const product = await api.get('/products/{productId}', { productId: variant.productId });
  return product.baseUomId ?? null;
}

/** Creates a movement (draft or posted at once) or edits a draft; the server checks every rule. */
export function MovementEditor({ movement, onDone }: { movement?: Movement; onDone: (id: string) => void }) {
  const { api } = useCompany();
  const form = useForm<MovementValues>({ resolver: zodResolver(movementSchema), defaultValues: movementValues(movement) });
  const [key] = useState(newIdempotencyKey);
  const type = useWatch({ control: form.control, name: 'movementType' });
  const warehouseId = useWatch({ control: form.control, name: 'warehouseId' });
  const destWarehouseId = useWatch({ control: form.control, name: 'destWarehouseId' });
  const isTransfer = type === 'TRANSFER' || type === 'TRANSFER_SHIP';
  const needsReason = type === 'ADJUSTMENT' || type === 'SCRAP';
  const showFrom = type !== 'OPENING';
  const showTo = type !== 'SCRAP';
  const showCost = type === 'OPENING' || type === 'ADJUSTMENT';
  const toWarehouse = isTransfer && destWarehouseId ? destWarehouseId : warehouseId;

  const { submit, ...problem } = useSubmit(form, async (v) => {
    if (movement) {
      await api.patch('/stock-movements/{movementId}', { movementId: movement.id! }, {
        body: {
          movementDate: v.movementDate,
          notes: v.notes || null,
          reasonCodeId: v.reasonCodeId,
          destWarehouseId: isTransfer ? v.destWarehouseId : undefined,
          lines: linesBody(v.lines),
        },
        ifMatch: movement.version,
      });
      notify.success(t('common.saved'));
      onDone(movement.id!);
      return;
    }
    const created = (await api.post('/stock-movements', null, {
      body: {
        movementType: v.movementType!,
        movementDate: v.movementDate!,
        warehouseId: v.warehouseId!,
        destWarehouseId: v.destWarehouseId ?? undefined,
        reasonCodeId: v.reasonCodeId ?? undefined,
        notes: v.notes || undefined,
        postImmediately: v.postImmediately || undefined,
        lines: linesBody(v.lines),
      },
      idempotencyKey: v.postImmediately ? key : undefined,
    })) as Movement;
    notify.success(t('common.created'));
    onDone(created.id!);
  });

  const columns: LineColumn[] = [
    {
      key: 'variant',
      header: t('inv.variant'),
      className: 'min-w-56',
      render: (i) => (
        <LineEntity
          name={`lines.${i}.variantId`}
          label={`${t('inv.variant')} ${i + 1}`}
          source={entities.variant}
          onSelect={(variant) => void baseUomOf(api, variant).then((uom) => uom && form.setValue(`lines.${i}.uomId`, uom))}
        />
      ),
    },
    ...(showFrom
      ? [{ key: 'from', header: t('inv.fromLocation'), className: 'min-w-44', render: (i: number) => <LineEntity name={`lines.${i}.fromLocationId`} label={`${t('inv.fromLocation')} ${i + 1}`} source={locationSource(warehouseId)} /> }]
      : []),
    ...(showTo
      ? [{ key: 'to', header: t('inv.toLocation'), className: 'min-w-44', render: (i: number) => <LineEntity name={`lines.${i}.toLocationId`} label={`${t('inv.toLocation')} ${i + 1}`} source={locationSource(toWarehouse)} /> }]
      : []),
    { key: 'qty', header: t('common.quantity'), align: 'right', render: (i) => <LineDecimal name={`lines.${i}.quantity`} label={`${t('common.quantity')} ${i + 1}`} /> },
    { key: 'uom', header: t('inv.uom'), className: 'min-w-28', render: (i) => <LineEntity name={`lines.${i}.uomId`} label={`${t('inv.uom')} ${i + 1}`} source={entities.uom} /> },
    ...(showCost ? [{ key: 'cost', header: t('inv.unitCost'), align: 'right' as const, render: (i: number) => <LineDecimal name={`lines.${i}.unitCostBase`} label={`${t('inv.unitCost')} ${i + 1}`} /> }] : []),
  ];

  return (
    <Form form={form} onSubmit={submit}>
      <Section>
        <FieldGrid columns={3}>
          <SelectField name="movementType" label={t('inv.movementType')} options={enumOptions(enums.manualMovementType)} required disabled={!!movement} />
          <DateField name="movementDate" label={t('inv.movementDate')} required />
          <EntityField name="warehouseId" label={t('common.warehouse')} source={entities.warehouse} required disabled={!!movement} />
          {isTransfer ? <EntityField name="destWarehouseId" label={t('inv.destWarehouse')} source={entities.warehouse} required={type === 'TRANSFER_SHIP'} /> : null}
          {needsReason ? <EntityField name="reasonCodeId" label={t('inv.reasonCode')} source={entities.reasonCode} filter={(r) => r.appliesTo === type} required /> : null}
        </FieldGrid>
        <div className="mt-4">
          <TextareaField name="notes" label={t('common.notes')} rows={2} />
        </div>
      </Section>
      <Section title={t('common.lines')}>
        <LinesEditor<MovementValues> name="lines" columns={columns} newLine={emptyLine} caption={t('common.lines')} />
      </Section>
      {!movement ? <CheckboxField name="postImmediately" label={t('inv.postImmediately')} /> : null}
      <FormProblem {...problem} />
      <div className="flex gap-2">
        <Button type="submit" disabled={form.formState.isSubmitting}>
          {form.formState.isSubmitting ? t('common.saving') : t('common.save')}
        </Button>
      </div>
    </Form>
  );
}

export function NewMovementPage() {
  const { companyId } = useCompany();
  const navigate = useNavigate();
  return (
    <div className="space-y-4">
      <PageHeader title={t('inv.newMovement')} breadcrumbs={<BackLink to={`/c/${companyId}/inventory/movements`} label={t('inv.movementsTitle')} />} />
      <MovementEditor onDone={(id) => void navigate({ to: '/c/$companyId/inventory/movements/$movementId', params: { companyId, movementId: id } })} />
    </div>
  );
}

// --- Detail -----------------------------------------------------------------------------------

const route = getRouteApi('/_authed/c/$companyId/inventory/movements/$movementId');

export function MovementPage() {
  const { movementId } = route.useParams();
  const { api, companyId, can } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['movements', movementId], (c, signal) => c.get('/stock-movements/{movementId}', { movementId }, { signal }));
  const [editing, setEditing] = useState(false);
  const [reversing, setReversing] = useState(false);
  const [receiving, setReceiving] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const m = query.data;
  const adjustment = m.movementType === 'ADJUSTMENT' || m.movementType === 'SCRAP';
  const postPerms = ['inventory.movement.post', ...(adjustment ? ['inventory.adjustment.manage'] : [])];

  const actions: DocAction<Movement>[] = [
    { id: 'edit', label: t('common.edit'), when: inStatus('DRAFT'), permissions: ['inventory.movement.create'], open: () => setEditing(true) },
    {
      id: 'post',
      label: t('inv.post'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: postPerms,
      confirm: { title: t('inv.postConfirm') },
      run: (c, x, ctx) => c.post('/stock-movements/{movementId}/post', { movementId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    {
      id: 'receive',
      label: t('inv.receive'),
      primary: true,
      when: (x) => x.movementType === 'TRANSFER_SHIP' && x.status === 'POSTED' && !x.relatedMovementId,
      permissions: ['inventory.movement.post'],
      open: () => setReceiving(true),
    },
    {
      id: 'cancel',
      label: t('inv.cancelMovement'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: postPerms,
      confirm: { title: t('inv.cancelMovement'), destructive: true },
      run: (c, x) => c.post('/stock-movements/{movementId}/cancel', { movementId: x.id! }, { ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('inv.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['inventory.movement.create'],
      confirm: { title: t('inv.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/stock-movements/{movementId}', { movementId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/inventory/movements', params: { companyId } }),
    },
    {
      id: 'reverse',
      label: t('inv.reverse'),
      variant: 'destructive',
      when: (x) => x.status === 'POSTED' && !x.reversalOfId,
      permissions: ['inventory.movement.reverse'],
      open: () => setReversing(true),
    },
  ];

  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/inventory/movements`} label={t('inv.movementsTitle')} />}
      title={m.number ?? `${enumLabel(m.movementType)} · ${t('enums.DRAFT')}`}
      badge={<StatusBadge status={m.status} />}
      description={enumLabel(m.movementType)}
      actions={
        <>
          <AuditHistoryButton entityType="stock_movement" entityId={m.id} />
          <DocumentActions doc={m} actions={actions} />
        </>
      }
      summary={
        editing ? undefined : (
          <DetailList
            items={[
              { label: t('inv.movementDate'), value: <DateText value={m.movementDate} /> },
              { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={m.warehouseId} /> },
              m.destWarehouseId ? { label: t('inv.destWarehouse'), value: <EntityName source={entities.warehouse} id={m.destWarehouseId} /> } : null,
              m.reasonCodeId ? { label: t('inv.reasonCode'), value: <EntityName source={entities.reasonCode} id={m.reasonCodeId} /> } : null,
              m.source ? { label: t('inv.source'), value: `${enumLabel(m.source.type)} ${m.source.number ?? ''}` } : null,
              m.partnerId ? { label: t('nav.partners'), value: <EntityName source={entities.partner} id={m.partnerId} /> } : null,
              m.reversalOfId ? { label: t('inv.reversalOf'), value: <MovementLink id={m.reversalOfId} number={t('common.view')} /> } : null,
              m.relatedMovementId ? { label: t('inv.related'), value: <MovementLink id={m.relatedMovementId} number={t('common.view')} /> } : null,
              m.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={m.postedAt} /> } : null,
              m.notes ? { label: t('common.notes'), value: m.notes, wide: true } : null,
            ]}
          />
        )
      }
      lines={
        editing ? undefined : (
          <LinesTable<MovementLine>
            lines={m.lines ?? []}
            rowKey={(l) => l.id!}
            caption={t('common.lines')}
            columns={[
              { id: 'no', header: '#', cell: (l) => l.lineNo },
              { id: 'variant', header: t('inv.variant'), cell: (l) => <EntityName source={entities.variant} id={l.variantId} /> },
              { id: 'from', header: t('inv.fromLocation'), hideBelow: 'md', cell: (l) => <EntityName source={locationById} id={l.fromLocationId} fallback="" /> },
              { id: 'to', header: t('inv.toLocation'), hideBelow: 'md', cell: (l) => <EntityName source={locationById} id={l.toLocationId} fallback="" /> },
              { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
              { id: 'uom', header: t('inv.uom'), cell: (l) => <EntityName source={entities.uom} id={l.uomId} /> },
              { id: 'base', header: t('inv.quantityBase'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.quantityBase} /> },
              ...(can('inventory.valuation.read') ? [{ id: 'cost', header: t('inv.unitCost'), align: 'right' as const, hideBelow: 'sm' as const, cell: (l: MovementLine) => <Money value={l.unitCostBase} showCurrency={false} /> }] : []),
            ]}
          />
        )
      }
    >
      {editing ? (
        <div className="space-y-2">
          <Button variant="ghost" onClick={() => setEditing(false)}>
            <Pencil aria-hidden />
            {t('common.cancel')}
          </Button>
          <MovementEditor movement={m} onDone={() => setEditing(false)} />
        </div>
      ) : null}
      <FormDialog
        open={reversing}
        onOpenChange={setReversing}
        title={t('inv.reverse')}
        description={t('inv.reverseConfirm')}
        schema={z.object({ movementDate: zf.optionalDate() })}
        defaults={{ movementDate: todayIso() }}
        destructive
        submitLabel={t('inv.reverse')}
        success={t('enums.REVERSED')}
        onSubmit={(v, key) =>
          api.post('/stock-movements/{movementId}/reverse', { movementId: m.id! }, { body: { movementDate: v.movementDate ?? undefined }, ifMatch: m.version, idempotencyKey: key })
        }
      >
        <DateField name="movementDate" label={t('inv.movementDate')} />
      </FormDialog>
      {receiving ? <ReceiveTransferDialog movement={m} onClose={() => setReceiving(false)} /> : null}
    </DocumentLayout>
  );
}

const receiveSchema = z.object({
  movementDate: zf.date(),
  lines: z.array(z.object({ shipLineId: z.string(), toLocationId: zf.id() })),
});

/** TRANSFER_SHIP → TRANSFER_RECEIVE: each shipped line gets its destination location. */
function ReceiveTransferDialog({ movement, onClose }: { movement: Movement; onClose: () => void }) {
  const { api } = useCompany();
  const lines = movement.lines ?? [];
  return (
    <FormDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('inv.receive')}
      description={t('inv.receiveText')}
      size="lg"
      schema={receiveSchema}
      defaults={{ movementDate: todayIso(), lines: lines.map((l) => ({ shipLineId: l.id!, toLocationId: null })) }}
      success={t('enums.RECEIVED')}
      onSubmit={(v, key) =>
        api.post('/stock-movements/{movementId}/receive', { movementId: movement.id! }, {
          body: { movementDate: v.movementDate!, lines: v.lines.map((l) => ({ shipLineId: l.shipLineId, toLocationId: l.toLocationId! })) },
          ifMatch: movement.version,
          idempotencyKey: key,
        })
      }
    >
      <DateField name="movementDate" label={t('inv.movementDate')} required />
      <ul className="space-y-3">
        {lines.map((l, i) => (
          <li key={l.id} className="grid gap-2 sm:grid-cols-2 sm:items-center">
            <div className="text-sm">
              <EntityName source={entities.variant} id={l.variantId} /> · <Quantity value={l.quantity} />
            </div>
            <LineEntity name={`lines.${i}.toLocationId`} label={`${t('inv.toLocation')} ${i + 1}`} source={locationSource(movement.destWarehouseId)} />
          </li>
        ))}
      </ul>
    </FormDialog>
  );
}

