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
import { documents, entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable, Totals } from '@/components/document/document-layout';
import { LineDecimal } from '@/components/document/lines-editor';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, EntityField, FieldGrid, Form, TextareaField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';
import { enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { TaxesSection } from '../procurement/bills';
import { pricedLineColumns, pricedLineSchema, pricedLinesBody, pricedLineValues, PricedLinesEditor } from '../shared/priced-lines';
import { SalesOrderLink } from './orders';

type Invoice = Schemas['Invoice'];

export function InvoiceLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/sales/invoices/$invoiceId" params={{ companyId, invoiceId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

const fromOrderSchema = z.object({ salesOrderId: zf.id() });

export function InvoicesPage() {
  const { api, can, companyId } = useCompany();
  const navigate = useNavigate();
  const [fromOrder, setFromOrder] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.invoicesTitle')} />
      <DataTable<Invoice>
        id="invoices"
        fetchPage={(c, query, signal) => c.get('/invoices', null, { query, signal })}
        rowKey={(i) => i.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'documentType', label: t('fields.documentType'), values: [...enums.invoiceDocumentType] },
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.documentStatus] },
          { kind: 'entity', key: 'customerId', label: t('sales.customer'), source: entities.customer },
          { kind: 'dateRange', field: 'invoiceDate', label: t('sales.invoiceDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (i) => <InvoiceLink id={i.id} number={i.number} /> },
          { id: 'type', header: t('fields.documentType'), hideBelow: 'md', cell: (i) => enumLabel(i.documentType) },
          { id: 'customer', header: t('sales.customer'), cell: (i) => <EntityName source={entities.customer} id={i.customerId} /> },
          { id: 'date', header: t('sales.invoiceDate'), sortKey: 'invoiceDate', hideBelow: 'sm', cell: (i) => <DateText value={i.invoiceDate} /> },
          { id: 'due', header: t('sales.dueDate'), sortKey: 'dueDate', hideBelow: 'lg', cell: (i) => <DateText value={i.dueDate} /> },
          { id: 'total', header: t('common.total'), sortKey: 'total', align: 'right', cell: (i) => <Money value={i.total} currency={i.currencyCode} /> },
          { id: 'status', header: t('common.status'), cell: (i) => <StatusBadge status={i.status} /> },
        ]}
        toolbar={
          can('sales.invoice.create') ? (
            <>
              {can('sales.invoice.create_direct') ? (
                <Button variant="outline" onClick={() => void navigate({ to: '/c/$companyId/sales/invoices/new', params: { companyId } })}>
                  <Plus aria-hidden />
                  {t('sales.newInvoice')}
                </Button>
              ) : null}
              <Button onClick={() => setFromOrder(true)}>
                <Plus aria-hidden />
                {t('sales.invoiceFromOrder')}
              </Button>
            </>
          ) : null
        }
      />
      <FormDialog
        open={fromOrder}
        onOpenChange={setFromOrder}
        title={t('sales.invoiceFromOrder')}
        schema={fromOrderSchema}
        defaults={{ salesOrderId: null }}
        onSubmit={(v) => api.post('/invoices/from-order', null, { body: { salesOrderId: v.salesOrderId! } })}
        onDone={(i) => void navigate({ to: '/c/$companyId/sales/invoices/$invoiceId', params: { companyId, invoiceId: (i as Invoice).id! } })}
      >
        <EntityField name="salesOrderId" label={t('sales.order')} source={documents.salesOrder} filters={{ 'invoiceStatus.in': ['NOT_INVOICED', 'PARTIALLY_INVOICED'] }} required />
      </FormDialog>
    </div>
  );
}

const directSchema = z.object({
  customerId: zf.id(),
  invoiceDate: zf.date(),
  dueDate: zf.optionalDate(),
  notes: zf.optionalText(2000),
  lines: z.array(pricedLineSchema).min(1, t('forms.required')),
});
type DirectValues = z.infer<typeof directSchema>;

/** A direct invoice without an order (services; needs sales.invoice.create_direct). */
export function NewInvoicePage() {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const form = useForm<DirectValues>({
    resolver: zodResolver(directSchema),
    defaultValues: { customerId: null, invoiceDate: todayIso(), dueDate: null, notes: '', lines: pricedLineValues(undefined) },
  });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    const invoice = await api.post('/invoices', null, {
      body: {
        documentType: 'INVOICE',
        customerId: v.customerId!,
        invoiceDate: v.invoiceDate ?? undefined,
        dueDate: v.dueDate ?? undefined,
        notes: v.notes || undefined,
        lines: pricedLinesBody(v.lines) as Schemas['InvoiceLineRequest'][],
      },
    });
    notify.success(t('doc.created'));
    await navigate({ to: '/c/$companyId/sales/invoices/$invoiceId', params: { companyId, invoiceId: invoice.id! } });
  });
  return (
    <div className="space-y-4">
      <PageHeader title={t('sales.newInvoice')} breadcrumbs={<BackLink to={`/c/${companyId}/sales/invoices`} label={t('sales.invoicesTitle')} />} />
      <Form form={form} onSubmit={submit}>
        <Section>
          <FieldGrid columns={3}>
            <EntityField name="customerId" label={t('sales.customer')} source={entities.customer} required />
            <DateField name="invoiceDate" label={t('sales.invoiceDate')} required />
            <DateField name="dueDate" label={t('sales.dueDate')} />
          </FieldGrid>
          <div className="mt-4">
            <TextareaField name="notes" label={t('doc.notes')} rows={2} />
          </div>
        </Section>
        <Section title={t('common.lines')}>
          <PricedLinesEditor<DirectValues> name="lines" side="sales" priceHint={t('sales.priceHint')} />
        </Section>
        <FormProblem {...problem} />
        <Button type="submit" disabled={form.formState.isSubmitting}>{t('doc.save')}</Button>
      </Form>
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/sales/invoices/$invoiceId');

export function InvoicePage() {
  const { invoiceId } = route.useParams();
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['invoices', invoiceId], (c, signal) => c.get('/invoices/{invoiceId}', { invoiceId }, { signal }));
  const settlement = useCompanyQuery(['invoices', invoiceId, 'settlement'], (c, signal) => c.get('/invoices/{invoiceId}/settlement', { invoiceId }, { signal }), {
    enabled: query.data?.status === 'POSTED',
  });
  const [editing, setEditing] = useState(false);
  const [crediting, setCrediting] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const inv = query.data;
  const actions: DocAction<Invoice>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: ['sales.invoice.create'], open: () => setEditing(true) },
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: ['sales.invoice.post'],
      confirm: { title: t('sales.postInvoiceConfirm') },
      run: (c, x, ctx) => c.post('/invoices/{invoiceId}/post', { invoiceId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    { id: 'credit', label: t('sales.newCreditNote'), when: (x) => x.status === 'POSTED' && x.documentType === 'INVOICE', permissions: ['sales.invoice.create'], open: () => setCrediting(true) },
    {
      id: 'cancel',
      label: t('doc.cancel'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['sales.invoice.post'],
      confirm: { title: t('doc.cancel'), destructive: true },
      run: (c, x) => c.post('/invoices/{invoiceId}/cancel', { invoiceId: x.id! }, { ifMatch: x.version }),
    },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['sales.invoice.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/invoices/{invoiceId}', { invoiceId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/sales/invoices', params: { companyId } }),
    },
  ];
  const editInitial = { invoiceDate: inv.invoiceDate ?? null, accountingDate: inv.accountingDate ?? null, dueDate: inv.dueDate ?? null, notes: inv.notes ?? '' };
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/sales/invoices`} label={t('sales.invoicesTitle')} />}
      title={inv.number ?? `${enumLabel(inv.documentType)} · ${t('doc.draft')}`}
      badge={<StatusBadge status={inv.status} />}
      description={
        <>
          {enumLabel(inv.documentType)} · <EntityName source={entities.customer} id={inv.customerId} />
        </>
      }
      actions={
        <>
          <AuditHistoryButton entityType="invoice" entityId={inv.id} />
          <DocumentActions doc={inv} actions={actions} />
        </>
      }
      summary={
        <DetailList
          items={[
            { label: t('sales.invoiceDate'), value: <DateText value={inv.invoiceDate} /> },
            { label: t('sales.accountingDate'), value: <DateText value={inv.accountingDate} /> },
            { label: t('sales.dueDate'), value: <DateText value={inv.dueDate} /> },
            { label: t('sales.order'), value: <SalesOrderLink id={inv.salesOrderId} number={t('common.view')} /> },
            inv.originalInvoiceId ? { label: t('sales.originalInvoice'), value: <InvoiceLink id={inv.originalInvoiceId} number={t('common.view')} /> } : null,
            { label: t('fields.taxRegistrationNo'), value: <Text value={inv.customerTaxRegistrationNo} /> },
            { label: t('doc.currency'), value: inv.currencyCode },
            inv.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={inv.postedAt} /> } : null,
            settlement.data ? { label: t('doc.openAmount'), value: <><Money value={settlement.data.openAmount} currency={inv.currencyCode} /> <StatusBadge status={settlement.data.status} /></> } : null,
            inv.notes ? { label: t('doc.notes'), value: inv.notes, wide: true } : null,
          ]}
        />
      }
      lines={
        <LinesTable<Schemas['InvoiceLine']>
          lines={inv.lines ?? []}
          rowKey={(l) => l.id!}
          columns={pricedLineColumns<Schemas['InvoiceLine']>(inv.currencyCode, [
            { id: 'credited', header: t('doc.credited'), align: 'right', hideBelow: 'lg', cell: (l) => <Quantity value={l.creditedQuantityBase} /> },
          ])}
        />
      }
      totals={
        <Totals
          currency={inv.currencyCode}
          rows={[
            { label: t('doc.subtotal'), value: inv.subtotal },
            { label: t('doc.taxTotal'), value: inv.taxTotal },
            { label: t('doc.grandTotal'), value: inv.total, strong: true },
          ]}
        />
      }
    >
      <TaxesSection taxes={inv.taxes ?? []} currency={inv.currencyCode} />
      <FormDialog
        open={editing}
        onOpenChange={setEditing}
        title={t('doc.edit')}
        schema={z.object({ invoiceDate: zf.date(), accountingDate: zf.optionalDate(), dueDate: zf.optionalDate(), notes: zf.optionalText(2000) })}
        defaults={editInitial}
        success={t('common.saved')}
        onSubmit={(v) => api.patch('/invoices/{invoiceId}', { invoiceId: inv.id! }, { body: mergePatch(editInitial, v), ifMatch: inv.version })}
      >
        <FieldGrid>
          <DateField name="invoiceDate" label={t('sales.invoiceDate')} required />
          <DateField name="accountingDate" label={t('sales.accountingDate')} />
          <DateField name="dueDate" label={t('sales.dueDate')} />
        </FieldGrid>
        <TextareaField name="notes" label={t('doc.notes')} rows={2} />
      </FormDialog>
      {crediting ? <CreditNoteDialog invoice={inv} onClose={() => setCrediting(false)} /> : null}
    </DocumentLayout>
  );
}

const creditSchema = z.object({ lines: z.array(z.object({ originalInvoiceLineId: z.string(), quantity: zf.optionalDecimal() })) });

function CreditNoteDialog({ invoice, onClose }: { invoice: Invoice; onClose: () => void }) {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const lines = invoice.lines ?? [];
  return (
    <FormDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('sales.newCreditNote')}
      description={t('sales.creditNoteText')}
      size="lg"
      schema={creditSchema}
      defaults={{ lines: lines.map((l) => ({ originalInvoiceLineId: l.id!, quantity: null })) }}
      onSubmit={(v) =>
        api.post('/invoices', null, {
          body: {
            documentType: 'CREDIT_NOTE',
            customerId: invoice.customerId!,
            originalInvoiceId: invoice.id!,
            lines: v.lines.filter((l) => l.quantity).map((l) => ({ originalInvoiceLineId: l.originalInvoiceLineId, quantity: l.quantity! })),
          },
        })
      }
      onDone={(cn) => void navigate({ to: '/c/$companyId/sales/invoices/$invoiceId', params: { companyId, invoiceId: (cn as Invoice).id! } })}
    >
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
