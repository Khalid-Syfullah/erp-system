import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm, useFormContext } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Money, Quantity } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable } from '@/components/document/document-layout';
import { LineDecimal, LineEntity, LinesEditor, LineText, type LineColumn } from '@/components/document/lines-editor';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, EntityField, FieldGrid, Form, TextareaField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { enums } from '@/lib/enums';

type Requisition = Schemas['Requisition'];
type RequisitionLine = Schemas['RequisitionLine'];

export function RequisitionsPage() {
  const { can, companyId } = useCompany();
  const navigate = useNavigate();
  const create = can('procurement.requisition.create') ? (
    <Button onClick={() => void navigate({ to: '/c/$companyId/procurement/requisitions/new', params: { companyId } })}>
      <Plus aria-hidden />
      {t('proc.newRequisition')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.requisitionsTitle')} />
      <DataTable<Requisition>
        id="requisitions"
        fetchPage={(api, query, signal) => api.get('/purchase-requisitions', null, { query, signal })}
        rowKey={(r) => r.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.requisitionStatus] },
          { kind: 'entity', key: 'branchId', label: t('common.branch'), source: entities.branch },
          { kind: 'entity', key: 'departmentId', label: t('common.department'), source: entities.department },
        ]}
        columns={[
          {
            id: 'number',
            header: t('common.number'),
            cell: (r) => (
              <Link to="/c/$companyId/procurement/requisitions/$requisitionId" params={{ companyId, requisitionId: r.id! }} data-row-link className="font-medium text-primary hover:underline">
                {r.number ?? t('doc.draft')}
              </Link>
            ),
          },
          { id: 'branch', header: t('common.branch'), cell: (r) => <EntityName source={entities.branch} id={r.branchId} /> },
          { id: 'department', header: t('common.department'), hideBelow: 'md', cell: (r) => <EntityName source={entities.department} id={r.departmentId} /> },
          { id: 'needed', header: t('proc.neededBy'), hideBelow: 'sm', cell: (r) => <DateText value={r.neededBy} /> },
          { id: 'lines', header: t('common.lines'), align: 'right', hideBelow: 'sm', cell: (r) => r.lines?.length ?? 0 },
          { id: 'status', header: t('common.status'), cell: (r) => <StatusBadge status={r.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
    </div>
  );
}

const lineSchema = z.object({
  variantId: zf.id(),
  description: zf.optionalText(500),
  quantity: zf.decimal(),
  uomId: zf.id(),
  estimatedUnitPrice: zf.optionalDecimal(),
  suggestedSupplierId: zf.optionalId(),
});
const schema = z.object({
  branchId: zf.id(),
  departmentId: zf.optionalId(),
  neededBy: zf.optionalDate(),
  notes: zf.optionalText(2000),
  lines: z.array(lineSchema).min(1, t('forms.required')),
});
type Values = z.infer<typeof schema>;
const emptyLine = (): Values['lines'][number] => ({ variantId: null, description: '', quantity: '1', uomId: null, estimatedUnitPrice: null, suggestedSupplierId: null });

function values(r?: Requisition): Values {
  return {
    branchId: r?.branchId ?? null,
    departmentId: r?.departmentId ?? null,
    neededBy: r?.neededBy ?? null,
    notes: r?.notes ?? '',
    lines: r?.lines?.length
      ? r.lines.map((l) => ({
          variantId: l.variantId ?? null,
          description: l.description ?? '',
          quantity: l.quantity ?? null,
          uomId: l.uomId ?? null,
          estimatedUnitPrice: l.estimatedUnitPrice ?? null,
          suggestedSupplierId: l.suggestedSupplierId ?? null,
        }))
      : [emptyLine()],
  };
}

function linesBody(lines: Values['lines']) {
  return lines.map((l) => ({
    variantId: l.variantId!,
    description: l.description || undefined,
    quantity: l.quantity!,
    uomId: l.uomId!,
    estimatedUnitPrice: l.estimatedUnitPrice || undefined,
    suggestedSupplierId: l.suggestedSupplierId ?? undefined,
  }));
}

function RequisitionLines() {
  const { api } = useCompany();
  const form = useFormContext<Values>();
  const columns: LineColumn[] = [
    {
      key: 'variant',
      header: t('doc.item'),
      className: 'min-w-56',
      render: (i) => (
        <LineEntity
          name={`lines.${i}.variantId`}
          label={`${t('doc.item')} ${i + 1}`}
          source={entities.variant}
          onSelect={(v) =>
            v?.productId &&
            void api.get('/products/{productId}', { productId: v.productId }).then((p) => form.setValue(`lines.${i}.uomId`, p.purchaseUomId ?? p.baseUomId ?? null))
          }
        />
      ),
    },
    { key: 'description', header: t('doc.description'), render: (i) => <LineText name={`lines.${i}.description`} label={`${t('doc.description')} ${i + 1}`} /> },
    { key: 'qty', header: t('common.quantity'), align: 'right', render: (i) => <LineDecimal name={`lines.${i}.quantity`} label={`${t('common.quantity')} ${i + 1}`} /> },
    { key: 'uom', header: t('inv.uom'), className: 'min-w-28', render: (i) => <LineEntity name={`lines.${i}.uomId`} label={`${t('inv.uom')} ${i + 1}`} source={entities.uom} /> },
    { key: 'price', header: t('proc.estimatedPrice'), align: 'right', render: (i) => <LineDecimal name={`lines.${i}.estimatedUnitPrice`} label={`${t('proc.estimatedPrice')} ${i + 1}`} /> },
    { key: 'supplier', header: t('proc.suggestedSupplier'), className: 'min-w-44', render: (i) => <LineEntity name={`lines.${i}.suggestedSupplierId`} label={`${t('proc.suggestedSupplier')} ${i + 1}`} source={entities.supplier} /> },
  ];
  return <LinesEditor<Values> name="lines" columns={columns} newLine={emptyLine} />;
}

function RequisitionEditor({ requisition, onDone }: { requisition?: Requisition; onDone: (id: string) => void }) {
  const { api } = useCompany();
  const initial = values(requisition);
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: initial });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    const { lines, ...header } = v;
    if (requisition) {
      const { lines: _l, ...before } = initial;
      await api.patch('/purchase-requisitions/{requisitionId}', { requisitionId: requisition.id! }, { body: { ...mergePatch(before, header), lines: linesBody(lines) }, ifMatch: requisition.version });
      notify.success(t('common.saved'));
      onDone(requisition.id!);
    } else {
      const created = await api.post('/purchase-requisitions', null, {
        body: { branchId: header.branchId!, departmentId: header.departmentId ?? undefined, neededBy: header.neededBy ?? undefined, notes: header.notes || undefined, lines: linesBody(lines) },
      });
      notify.success(t('doc.created'));
      onDone(created.id!);
    }
  });
  return (
    <Form form={form} onSubmit={submit}>
      <Section>
        <FieldGrid columns={3}>
          <EntityField name="branchId" label={t('common.branch')} source={entities.branch} required />
          <EntityField name="departmentId" label={t('common.department')} source={entities.department} />
          <DateField name="neededBy" label={t('proc.neededBy')} />
        </FieldGrid>
        <div className="mt-4">
          <TextareaField name="notes" label={t('doc.notes')} rows={2} />
        </div>
      </Section>
      <Section title={t('common.lines')}>
        <RequisitionLines />
      </Section>
      <FormProblem {...problem} />
      <Button type="submit" disabled={form.formState.isSubmitting}>{t('doc.save')}</Button>
    </Form>
  );
}

export function NewRequisitionPage() {
  const { companyId } = useCompany();
  const navigate = useNavigate();
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.newRequisition')} breadcrumbs={<BackLink to={`/c/${companyId}/procurement/requisitions`} label={t('proc.requisitionsTitle')} />} />
      <RequisitionEditor onDone={(id) => void navigate({ to: '/c/$companyId/procurement/requisitions/$requisitionId', params: { companyId, requisitionId: id } })} />
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/procurement/requisitions/$requisitionId');
const convertSchema = z.object({ supplierId: zf.id(), warehouseId: zf.id() });

export function RequisitionPage() {
  const { requisitionId } = route.useParams();
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['requisitions', requisitionId], (c, signal) => c.get('/purchase-requisitions/{requisitionId}', { requisitionId }, { signal }));
  const [editing, setEditing] = useState(false);
  const [converting, setConverting] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const r = query.data;
  const perm = (p: string) => [`procurement.requisition.${p}`];
  const actions: DocAction<Requisition>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: perm('create'), open: () => setEditing(true) },
    { id: 'submit', label: t('doc.submit'), primary: true, when: inStatus('DRAFT'), permissions: perm('create'), run: (c, x) => c.post('/purchase-requisitions/{requisitionId}/submit', { requisitionId: x.id! }, { ifMatch: x.version }) },
    { id: 'approve', label: t('doc.approve'), primary: true, when: inStatus('SUBMITTED'), permissions: perm('approve'), run: (c, x) => c.post('/purchase-requisitions/{requisitionId}/approve', { requisitionId: x.id! }, { ifMatch: x.version }), success: t('enums.APPROVED') },
    {
      id: 'reject',
      label: t('doc.reject'),
      variant: 'destructive',
      when: inStatus('SUBMITTED'),
      permissions: perm('approve'),
      confirm: { title: t('doc.reject'), reason: 'required', reasonLabel: t('doc.rejectReason'), destructive: true },
      run: (c, x, ctx) => c.post('/purchase-requisitions/{requisitionId}/reject', { requisitionId: x.id! }, { body: { reason: ctx.reason }, ifMatch: x.version }),
    },
    { id: 'convert', label: t('proc.convert'), primary: true, when: inStatus('APPROVED', 'PARTIALLY_ORDERED'), permissions: ['procurement.purchase_order.create'], open: () => setConverting(true) },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT', 'SUBMITTED', 'APPROVED'),
      permissions: perm('create'),
      confirm: { title: t('doc.cancel'), reason: 'optional', destructive: true },
      run: (c, x, ctx) => c.post('/purchase-requisitions/{requisitionId}/cancel', { requisitionId: x.id! }, { body: { reason: ctx.reason || undefined }, ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: perm('create'),
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/purchase-requisitions/{requisitionId}', { requisitionId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/procurement/requisitions', params: { companyId } }),
    },
  ];
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/procurement/requisitions`} label={t('proc.requisitionsTitle')} />}
      title={r.number ?? t('proc.newRequisition')}
      badge={<StatusBadge status={r.status} />}
      actions={
        <>
          <AuditHistoryButton entityType="purchase_requisition" entityId={r.id} />
          <DocumentActions doc={r} actions={actions} />
        </>
      }
      summary={
        editing ? undefined : (
          <DetailList
            items={[
              { label: t('common.branch'), value: <EntityName source={entities.branch} id={r.branchId} /> },
              { label: t('common.department'), value: <EntityName source={entities.department} id={r.departmentId} /> },
              { label: t('proc.neededBy'), value: <DateText value={r.neededBy} /> },
              r.submittedAt ? { label: t('enums.SUBMITTED'), value: <DateTimeText value={r.submittedAt} /> } : null,
              r.approvedAt ? { label: t('enums.APPROVED'), value: <DateTimeText value={r.approvedAt} /> } : null,
              r.rejectionReason ? { label: t('doc.rejectReason'), value: r.rejectionReason } : null,
              r.notes ? { label: t('doc.notes'), value: r.notes, wide: true } : null,
            ]}
          />
        )
      }
      lines={
        editing ? undefined : (
          <LinesTable<RequisitionLine>
            lines={r.lines ?? []}
            rowKey={(l) => l.id!}
            columns={[
              { id: 'no', header: '#', cell: (l) => l.lineNo },
              { id: 'item', header: t('doc.item'), cell: (l) => <EntityName source={entities.variant} id={l.variantId} /> },
              { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
              { id: 'uom', header: t('inv.uom'), cell: (l) => <EntityName source={entities.uom} id={l.uomId} /> },
              { id: 'price', header: t('proc.estimatedPrice'), align: 'right', hideBelow: 'sm', cell: (l) => <Money value={l.estimatedUnitPrice} showCurrency={false} /> },
              { id: 'supplier', header: t('proc.suggestedSupplier'), hideBelow: 'md', cell: (l) => <EntityName source={entities.supplier} id={l.suggestedSupplierId} /> },
              { id: 'ordered', header: t('proc.ordered'), align: 'right', hideBelow: 'md', cell: (l) => <Quantity value={l.orderedQuantityBase} /> },
            ]}
          />
        )
      }
    >
      {editing ? (
        <div className="space-y-2">
          <Button variant="ghost" onClick={() => setEditing(false)}>{t('common.cancel')}</Button>
          <RequisitionEditor requisition={r} onDone={() => setEditing(false)} />
        </div>
      ) : null}
      <FormDialog
        open={converting}
        onOpenChange={setConverting}
        title={t('proc.convert')}
        description={t('proc.convertText')}
        schema={convertSchema}
        defaults={{ supplierId: r.lines?.find((l) => l.suggestedSupplierId)?.suggestedSupplierId ?? null, warehouseId: null }}
        onSubmit={(v, key) =>
          api.post('/purchase-requisitions/{requisitionId}/convert', { requisitionId: r.id! }, { body: { supplierId: v.supplierId!, warehouseId: v.warehouseId! }, ifMatch: r.version, idempotencyKey: key })
        }
        onDone={(order) => void navigate({ to: '/c/$companyId/procurement/orders/$orderId', params: { companyId, orderId: (order as Schemas['PurchaseOrder']).id! } })}
      >
        <EntityField name="supplierId" label={t('proc.supplier')} source={entities.supplier} required />
        <EntityField name="warehouseId" label={t('common.warehouse')} source={entities.warehouse} required />
      </FormDialog>
    </DocumentLayout>
  );
}
