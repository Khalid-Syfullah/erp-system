import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Money, Percent, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { documents, entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable, Totals } from '@/components/document/document-layout';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, DateField, EntityField, FieldGrid, Form, SelectField, TextareaField, TextField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Modal } from '@/components/overlay/modal';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { pricedLineColumns, pricedLineSchema, pricedLinesBody, pricedLineValues, PricedLinesEditor } from '../shared/priced-lines';
import { PurchaseOrderLink } from './purchase-orders';

type Bill = Schemas['SupplierBill'];
type BillLine = Schemas['SupplierBillLine'];

export function BillLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/procurement/bills/$billId" params={{ companyId, billId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

const fromReceiptsSchema = z.object({ goodsReceiptId: zf.id(), supplierInvoiceNumber: zf.text(50), billDate: zf.date() });

export function SupplierBillsPage() {
  const { api, can, companyId } = useCompany();
  const navigate = useNavigate();
  const [fromReceipts, setFromReceipts] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.billsTitle')} />
      <DataTable<Bill>
        id="supplier-bills"
        fetchPage={(c, query, signal) => c.get('/supplier-bills', null, { query, signal })}
        rowKey={(b) => b.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'documentType', label: t('fields.documentType'), values: [...enums.billDocumentType] },
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.documentStatus] },
          { kind: 'enum', key: 'matchStatus', label: t('proc.matchStatus'), values: [...enums.matchStatus] },
          { kind: 'entity', key: 'supplierId', label: t('proc.supplier'), source: entities.supplier },
          { kind: 'dateRange', field: 'dueDate', label: t('proc.dueDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (b) => <BillLink id={b.id} number={b.number} /> },
          { id: 'type', header: t('fields.documentType'), hideBelow: 'md', cell: (b) => enumLabel(b.documentType) },
          { id: 'supplier', header: t('proc.supplier'), cell: (b) => <EntityName source={entities.supplier} id={b.supplierId} /> },
          { id: 'invoice', header: t('proc.supplierInvoiceNumber'), hideBelow: 'sm', cell: (b) => <Text value={b.supplierInvoiceNumber} /> },
          { id: 'date', header: t('proc.billDate'), sortKey: 'billDate', hideBelow: 'md', cell: (b) => <DateText value={b.billDate} /> },
          { id: 'due', header: t('proc.dueDate'), sortKey: 'dueDate', hideBelow: 'lg', cell: (b) => <DateText value={b.dueDate} /> },
          { id: 'total', header: t('common.total'), sortKey: 'total', align: 'right', cell: (b) => <Money value={b.total} currency={b.currencyCode} /> },
          { id: 'match', header: t('proc.matchStatus'), hideBelow: 'sm', cell: (b) => <StatusBadge status={b.matchStatus} /> },
          { id: 'status', header: t('common.status'), cell: (b) => <StatusBadge status={b.status} /> },
        ]}
        toolbar={
          can('procurement.supplier_bill.create') ? (
            <>
              <Button variant="outline" onClick={() => void navigate({ to: '/c/$companyId/procurement/bills/new', params: { companyId } })}>
                <Plus aria-hidden />
                {t('proc.newBill')}
              </Button>
              <Button onClick={() => setFromReceipts(true)}>
                <Plus aria-hidden />
                {t('proc.billFromReceipts')}
              </Button>
            </>
          ) : null
        }
      />
      <FormDialog
        open={fromReceipts}
        onOpenChange={setFromReceipts}
        title={t('proc.billFromReceipts')}
        schema={fromReceiptsSchema}
        defaults={{ goodsReceiptId: null, supplierInvoiceNumber: '', billDate: todayIso() }}
        onSubmit={(v) =>
          api.post('/supplier-bills/from-receipts', null, { body: { goodsReceiptIds: [v.goodsReceiptId!], supplierInvoiceNumber: v.supplierInvoiceNumber, billDate: v.billDate ?? undefined } })
        }
        onDone={(b) => void navigate({ to: '/c/$companyId/procurement/bills/$billId', params: { companyId, billId: (b as Bill).id! } })}
      >
        <EntityField name="goodsReceiptId" label={t('proc.receipt')} source={documents.goodsReceipt} filters={{ status: 'POSTED' }} required />
        <TextField name="supplierInvoiceNumber" label={t('proc.supplierInvoiceNumber')} required />
        <DateField name="billDate" label={t('proc.billDate')} required />
      </FormDialog>
    </div>
  );
}

const billSchema = z.object({
  documentType: zf.id(),
  supplierId: zf.id(),
  supplierInvoiceNumber: zf.text(50),
  billDate: zf.date(),
  accountingDate: zf.optionalDate(),
  dueDate: zf.optionalDate(),
  purchaseOrderId: zf.optionalId(),
  originalBillId: zf.optionalId(),
  pricesIncludeTax: z.boolean(),
  notes: zf.optionalText(2000),
  lines: z.array(pricedLineSchema.extend({ unitPrice: zf.decimal() })).min(1, t('forms.required')),
});
type BillValues = z.infer<typeof billSchema>;

/** A direct bill or debit note (lines by item); bills from receipts are created from the receipt. */
export function NewSupplierBillPage() {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const form = useForm<BillValues>({
    resolver: zodResolver(billSchema),
    defaultValues: {
      documentType: 'BILL', supplierId: null, supplierInvoiceNumber: '', billDate: todayIso(), accountingDate: null, dueDate: null,
      purchaseOrderId: null, originalBillId: null, pricesIncludeTax: false, notes: '', lines: pricedLineValues(undefined) as BillValues['lines'],
    },
  });
  const type = useWatch({ control: form.control, name: 'documentType' });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    const bill = await api.post('/supplier-bills', null, {
      body: {
        documentType: v.documentType!,
        supplierId: v.supplierId!,
        supplierInvoiceNumber: v.supplierInvoiceNumber,
        billDate: v.billDate!,
        accountingDate: v.accountingDate ?? undefined,
        dueDate: v.dueDate ?? undefined,
        purchaseOrderId: v.purchaseOrderId ?? undefined,
        originalBillId: v.originalBillId ?? undefined,
        pricesIncludeTax: v.pricesIncludeTax || undefined,
        notes: v.notes || undefined,
        lines: pricedLinesBody(v.lines) as Schemas['SupplierBillLineRequest'][],
      },
    });
    notify.success(t('doc.created'));
    await navigate({ to: '/c/$companyId/procurement/bills/$billId', params: { companyId, billId: bill.id! } });
  });
  return (
    <div className="space-y-4">
      <PageHeader title={t('proc.newBill')} breadcrumbs={<BackLink to={`/c/${companyId}/procurement/bills`} label={t('proc.billsTitle')} />} />
      <Form form={form} onSubmit={submit}>
        <Section>
          <FieldGrid columns={3}>
            <SelectField name="documentType" label={t('fields.documentType')} options={enumOptions(enums.billDocumentType)} required />
            <EntityField name="supplierId" label={t('proc.supplier')} source={entities.supplier} required />
            <TextField name="supplierInvoiceNumber" label={t('proc.supplierInvoiceNumber')} required />
            <DateField name="billDate" label={t('proc.billDate')} required />
            <DateField name="accountingDate" label={t('proc.accountingDate')} />
            <DateField name="dueDate" label={t('proc.dueDate')} />
            <EntityField name="purchaseOrderId" label={t('proc.order')} source={documents.purchaseOrder} />
            {type === 'DEBIT_NOTE' ? <EntityField name="originalBillId" label={t('proc.originalBill')} source={documents.supplierBill} filters={{ status: 'POSTED', documentType: 'BILL' }} required /> : null}
          </FieldGrid>
          <div className="mt-4 space-y-4">
            <CheckboxField name="pricesIncludeTax" label={t('doc.pricesIncludeTax')} />
            <TextareaField name="notes" label={t('doc.notes')} rows={2} />
          </div>
        </Section>
        <Section title={t('common.lines')}>
          <PricedLinesEditor<BillValues> name="lines" side="purchase" />
        </Section>
        <FormProblem {...problem} />
        <Button type="submit" disabled={form.formState.isSubmitting}>{t('doc.save')}</Button>
      </Form>
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/procurement/bills/$billId');

export function SupplierBillPage() {
  const { billId } = route.useParams();
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['supplier-bills', billId], (c, signal) => c.get('/supplier-bills/{billId}', { billId }, { signal }));
  const settlement = useCompanyQuery(['supplier-bills', billId, 'settlement'], (c, signal) => c.get('/supplier-bills/{billId}/settlement', { billId }, { signal }), {
    enabled: query.data?.status === 'POSTED',
  });
  const [match, setMatch] = useState<Schemas['MatchResult'] | null>(null);
  const [editing, setEditing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const b = query.data;
  const actions: DocAction<Bill>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: ['procurement.supplier_bill.create'], open: () => setEditing(true) },
    {
      id: 'check',
      label: t('proc.checkMatch'),
      when: inStatus('DRAFT'),
      permissions: ['procurement.supplier_bill.create'],
      run: (c, x) => c.post('/supplier-bills/{billId}/check-match', { billId: x.id! }, { ifMatch: x.version }),
      onSuccess: (r) => setMatch(r as Schemas['MatchResult']),
    },
    {
      id: 'override',
      label: t('proc.overrideMatch'),
      when: (x) => x.status === 'DRAFT' && x.matchStatus === 'EXCEPTION',
      permissions: ['procurement.supplier_bill.override_match'],
      confirm: { title: t('proc.overrideMatch'), reason: 'required', reasonLabel: t('proc.overrideReason') },
      run: (c, x, ctx) => c.post('/supplier-bills/{billId}/override-match', { billId: x.id! }, { body: { reason: ctx.reason }, ifMatch: x.version }),
    },
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: ['procurement.supplier_bill.post'],
      confirm: { title: t('doc.postConfirm') },
      run: (c, x, ctx) => c.post('/supplier-bills/{billId}/post', { billId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['procurement.supplier_bill.post'],
      confirm: { title: t('doc.cancel'), destructive: true },
      run: (c, x) => c.post('/supplier-bills/{billId}/cancel', { billId: x.id! }, { ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['procurement.supplier_bill.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/supplier-bills/{billId}', { billId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/procurement/bills', params: { companyId } }),
    },
  ];
  const editSchema = z.object({ supplierInvoiceNumber: zf.text(50), billDate: zf.date(), accountingDate: zf.optionalDate(), dueDate: zf.optionalDate(), notes: zf.optionalText(2000) });
  const editInitial = { supplierInvoiceNumber: b.supplierInvoiceNumber ?? '', billDate: b.billDate ?? null, accountingDate: b.accountingDate ?? null, dueDate: b.dueDate ?? null, notes: b.notes ?? '' };
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/procurement/bills`} label={t('proc.billsTitle')} />}
      title={b.number ?? `${enumLabel(b.documentType)} · ${t('doc.draft')}`}
      badge={
        <>
          <StatusBadge status={b.status} />
          <StatusBadge status={b.matchStatus} />
        </>
      }
      description={
        <>
          {enumLabel(b.documentType)} · <EntityName source={entities.supplier} id={b.supplierId} />
        </>
      }
      actions={
        <>
          <AuditHistoryButton entityType="supplier_bill" entityId={b.id} />
          <DocumentActions doc={b} actions={actions} />
        </>
      }
      summary={
        <DetailList
          items={[
            { label: t('proc.supplierInvoiceNumber'), value: b.supplierInvoiceNumber },
            { label: t('proc.billDate'), value: <DateText value={b.billDate} /> },
            { label: t('proc.accountingDate'), value: <DateText value={b.accountingDate} /> },
            { label: t('proc.dueDate'), value: <DateText value={b.dueDate} /> },
            { label: t('proc.order'), value: <PurchaseOrderLink id={b.purchaseOrderId} number={t('common.view')} /> },
            b.originalBillId ? { label: t('proc.originalBill'), value: <BillLink id={b.originalBillId} number={t('common.view')} /> } : null,
            { label: t('doc.currency'), value: b.currencyCode },
            b.exchangeRate && b.exchangeRate !== '1' ? { label: t('doc.exchangeRate'), value: b.exchangeRate } : null,
            b.matchOverrideReason ? { label: t('proc.overrideReason'), value: b.matchOverrideReason } : null,
            b.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={b.postedAt} /> } : null,
            settlement.data ? { label: t('doc.openAmount'), value: <><Money value={settlement.data.openAmount} currency={b.currencyCode} /> <StatusBadge status={settlement.data.status} /></> } : null,
            b.notes ? { label: t('doc.notes'), value: b.notes, wide: true } : null,
          ]}
        />
      }
      lines={
        <LinesTable<BillLine>
          lines={b.lines ?? []}
          rowKey={(l) => l.id!}
          columns={pricedLineColumns<BillLine>(b.currencyCode, [
            { id: 'kind', header: t('proc.lineKind'), hideBelow: 'lg', cell: (l) => enumLabel(l.lineKind) },
            { id: 'receipt', header: t('proc.receiptValue'), align: 'right', hideBelow: 'lg', cell: (l) => <Money value={l.receiptValueBase} showCurrency={false} /> },
          ])}
        />
      }
      totals={
        <Totals
          currency={b.currencyCode}
          rows={[
            { label: t('doc.subtotal'), value: b.subtotal },
            { label: t('doc.taxTotal'), value: b.taxTotal },
            { label: t('doc.grandTotal'), value: b.total, strong: true },
          ]}
        />
      }
    >
      <TaxesSection taxes={b.taxes ?? []} currency={b.currencyCode} />
      <Modal open={match !== null} onOpenChange={(open) => !open && setMatch(null)} title={match?.matchStatus === 'MATCHED' ? t('proc.matchPassed') : t('proc.matchIssues')}>
        {match && (match.issues ?? []).length > 0 ? (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>#</TableHead>
                <TableHead>{t('common.description')}</TableHead>
                <TableHead className="text-right">{t('proc.expected')}</TableHead>
                <TableHead className="text-right">{t('proc.actual')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(match.issues ?? []).map((issue, i) => (
                <TableRow key={i}>
                  <TableCell>{issue.lineNo}</TableCell>
                  <TableCell>{enumLabel(issue.problem)}</TableCell>
                  <TableCell className="text-right"><Quantity value={issue.expected} /></TableCell>
                  <TableCell className="text-right"><Quantity value={issue.actual} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : (
          <StatusBadge status={match?.matchStatus} />
        )}
      </Modal>
      <FormDialog
        open={editing}
        onOpenChange={setEditing}
        title={t('doc.edit')}
        schema={editSchema}
        defaults={editInitial}
        success={t('common.saved')}
        onSubmit={(v) => api.patch('/supplier-bills/{billId}', { billId: b.id! }, { body: mergePatch(editInitial, v), ifMatch: b.version })}
      >
        <TextField name="supplierInvoiceNumber" label={t('proc.supplierInvoiceNumber')} required />
        <FieldGrid>
          <DateField name="billDate" label={t('proc.billDate')} required />
          <DateField name="accountingDate" label={t('proc.accountingDate')} />
          <DateField name="dueDate" label={t('proc.dueDate')} />
        </FieldGrid>
        <TextareaField name="notes" label={t('doc.notes')} rows={2} />
      </FormDialog>
    </DocumentLayout>
  );
}

/** Tax per code of a bill or invoice, as the server computed it (PER_LINE or PER_DOCUMENT rounding). */
export function TaxesSection({
  taxes,
  currency,
}: {
  taxes: { taxCodeId?: string; ratePercent?: string; taxableAmount?: string; taxAmount?: string }[];
  currency: string | null | undefined;
}) {
  if (taxes.length === 0) return null;
  return (
    <Section title={t('doc.lineTaxes')} bodyClassName="p-0">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t('doc.tax')}</TableHead>
            <TableHead className="text-right">{t('doc.rate')}</TableHead>
            <TableHead className="text-right">{t('doc.taxableAmount')}</TableHead>
            <TableHead className="text-right">{t('doc.taxTotal')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {taxes.map((x) => (
            <TableRow key={x.taxCodeId}>
              <TableCell><EntityName source={entities.taxCode} id={x.taxCodeId} /></TableCell>
              <TableCell className="text-right"><Percent value={x.ratePercent} /></TableCell>
              <TableCell className="text-right"><Money value={x.taxableAmount} currency={currency} /></TableCell>
              <TableCell className="text-right"><Money value={x.taxAmount} currency={currency} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Section>
  );
}
