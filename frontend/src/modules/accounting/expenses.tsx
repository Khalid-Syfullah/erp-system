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
import { DateText, DateTimeText, Money, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable, Totals } from '@/components/document/document-layout';
import { LineDecimal, LineEntity, LinesEditor, LineText, type LineColumn } from '@/components/document/lines-editor';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, DateField, EntityField, FieldGrid, Form, TextareaField, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';
import { enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';
import { EntryLink } from './entries';

type Expense = Schemas['Expense'];

export function ExpensesPage() {
  const { can, companyId } = useCompany();
  const navigate = useNavigate();
  const create = can('accounting.expense.create') ? (
    <Button onClick={() => void navigate({ to: '/c/$companyId/accounting/expenses/new', params: { companyId } })}>
      <Plus aria-hidden />
      {t('acc.newExpense')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('acc.expensesTitle')} />
      <DataTable<Expense>
        id="expenses"
        fetchPage={(c, query, signal) => c.get('/expenses', null, { query, signal })}
        rowKey={(e) => e.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.expenseStatus] },
          { kind: 'entity', key: 'bankAccountId', label: t('acc.bankAccount'), source: entities.bankAccount },
          { kind: 'dateRange', field: 'expenseDate', label: t('acc.expenseDate') },
        ]}
        columns={[
          {
            id: 'number',
            header: t('common.number'),
            cell: (e) => (
              <Link to="/c/$companyId/accounting/expenses/$expenseId" params={{ companyId, expenseId: e.id! }} data-row-link className="font-medium text-primary hover:underline">
                {e.number ?? t('doc.draft')}
              </Link>
            ),
          },
          { id: 'payee', header: t('acc.payee'), cell: (e) => e.payeeName },
          { id: 'date', header: t('acc.expenseDate'), sortKey: 'expenseDate', hideBelow: 'sm', cell: (e) => <DateText value={e.expenseDate} /> },
          { id: 'bank', header: t('acc.bankAccount'), hideBelow: 'md', cell: (e) => <EntityName source={entities.bankAccount} id={e.bankAccountId} /> },
          { id: 'total', header: t('common.total'), align: 'right', cell: (e) => <Money value={e.total} currency={e.currencyCode} /> },
          { id: 'status', header: t('common.status'), cell: (e) => <StatusBadge status={e.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
    </div>
  );
}

const lineSchema = z.object({ accountId: zf.id(), description: zf.optionalText(500), amount: zf.decimal(), taxCodeId: zf.optionalId(), branchId: zf.optionalId(), departmentId: zf.optionalId() });
const schema = z.object({
  expenseDate: zf.date(),
  accountingDate: zf.optionalDate(),
  payeeName: zf.text(200),
  partnerId: zf.optionalId(),
  bankAccountId: zf.id(),
  pricesIncludeTax: z.boolean(),
  reference: zf.optionalText(100),
  notes: zf.optionalText(1000),
  lines: z.array(lineSchema).min(1, t('forms.required')),
});
type Values = z.infer<typeof schema>;
const emptyLine = (): Values['lines'][number] => ({ accountId: null, description: '', amount: null, taxCodeId: null, branchId: null, departmentId: null });

export function NewExpensePage() {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { expenseDate: todayIso(), accountingDate: null, payeeName: '', partnerId: null, bankAccountId: null, pricesIncludeTax: false, reference: '', notes: '', lines: [emptyLine()] },
  });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    const expense = await api.post('/expenses', null, {
      body: {
        expenseDate: v.expenseDate ?? undefined,
        accountingDate: v.accountingDate ?? undefined,
        payeeName: v.payeeName,
        partnerId: v.partnerId ?? undefined,
        bankAccountId: v.bankAccountId!,
        pricesIncludeTax: v.pricesIncludeTax || undefined,
        reference: v.reference || undefined,
        notes: v.notes || undefined,
        lines: v.lines.map((l) => ({
          accountId: l.accountId!,
          description: l.description || undefined,
          amount: l.amount!,
          taxCodeId: l.taxCodeId ?? undefined,
          branchId: l.branchId ?? undefined,
          departmentId: l.departmentId ?? undefined,
        })),
      },
    });
    notify.success(t('doc.created'));
    await navigate({ to: '/c/$companyId/accounting/expenses/$expenseId', params: { companyId, expenseId: expense.id! } });
  });
  const columns: LineColumn[] = [
    { key: 'account', header: t('acc.account'), className: 'min-w-64', render: (i) => <LineEntity name={`lines.${i}.accountId`} label={`${t('acc.account')} ${i + 1}`} source={entities.account} filter={(a) => a.accountType === 'EXPENSE' || a.accountType === 'ASSET'} /> },
    { key: 'description', header: t('common.description'), render: (i) => <LineText name={`lines.${i}.description`} label={`${t('common.description')} ${i + 1}`} /> },
    { key: 'amount', header: t('common.amount'), align: 'right', render: (i) => <LineDecimal name={`lines.${i}.amount`} label={`${t('common.amount')} ${i + 1}`} /> },
    { key: 'tax', header: t('doc.tax'), className: 'min-w-36', render: (i) => <LineEntity name={`lines.${i}.taxCodeId`} label={`${t('doc.tax')} ${i + 1}`} source={entities.taxCode} filter={(x) => x.scope !== 'SALES'} /> },
    { key: 'branch', header: t('common.branch'), className: 'min-w-36', render: (i) => <LineEntity name={`lines.${i}.branchId`} label={`${t('common.branch')} ${i + 1}`} source={entities.branch} /> },
    { key: 'department', header: t('common.department'), className: 'min-w-36', render: (i) => <LineEntity name={`lines.${i}.departmentId`} label={`${t('common.department')} ${i + 1}`} source={entities.department} /> },
  ];
  return (
    <div className="space-y-4">
      <PageHeader title={t('acc.newExpense')} breadcrumbs={<BackLink to={`/c/${companyId}/accounting/expenses`} label={t('acc.expensesTitle')} />} />
      <Form form={form} onSubmit={submit}>
        <Section>
          <FieldGrid columns={3}>
            <TextField name="payeeName" label={t('acc.payee')} required />
            <EntityField name="partnerId" label={t('acc.partner')} source={entities.partner} />
            <EntityField name="bankAccountId" label={t('acc.bankAccount')} source={entities.bankAccount} required />
            <DateField name="expenseDate" label={t('acc.expenseDate')} required />
            <DateField name="accountingDate" label={t('sales.accountingDate')} />
            <TextField name="reference" label={t('acc.reference')} />
          </FieldGrid>
          <div className="mt-4 space-y-4">
            <CheckboxField name="pricesIncludeTax" label={t('doc.pricesIncludeTax')} />
            <TextareaField name="notes" label={t('common.notes')} rows={2} />
          </div>
        </Section>
        <Section title={t('common.lines')}>
          <LinesEditor<Values> name="lines" columns={columns} newLine={emptyLine} />
        </Section>
        <FormProblem {...problem} />
        <Button type="submit" disabled={form.formState.isSubmitting}>{t('doc.save')}</Button>
      </Form>
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/accounting/expenses/$expenseId');

export function ExpensePage() {
  const { expenseId } = route.useParams();
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['expenses', expenseId], (c, signal) => c.get('/expenses/{expenseId}', { expenseId }, { signal }));
  const [reversing, setReversing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const e = query.data;
  const actions: DocAction<Expense>[] = [
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: ['accounting.expense.post'],
      confirm: { title: t('doc.postConfirm') },
      run: (c, x, ctx) => c.post('/expenses/{expenseId}/post', { expenseId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    { id: 'reverse', label: t('acc.reverse'), variant: 'destructive', when: inStatus('POSTED'), permissions: ['accounting.expense.post'], open: () => setReversing(true) },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['accounting.expense.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/expenses/{expenseId}', { expenseId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/accounting/expenses', params: { companyId } }),
    },
  ];
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/accounting/expenses`} label={t('acc.expensesTitle')} />}
      title={e.number ?? t('acc.newExpense')}
      badge={<StatusBadge status={e.status} />}
      description={e.payeeName}
      actions={
        <>
          <AuditHistoryButton entityType="expense" entityId={e.id} />
          <DocumentActions doc={e} actions={actions} />
        </>
      }
      summary={
        <DetailList
          items={[
            { label: t('acc.expenseDate'), value: <DateText value={e.expenseDate} /> },
            { label: t('acc.bankAccount'), value: <EntityName source={entities.bankAccount} id={e.bankAccountId} /> },
            { label: t('acc.partner'), value: <EntityName source={entities.partner} id={e.partnerId} /> },
            { label: t('acc.reference'), value: <Text value={e.reference} /> },
            e.journalEntryId ? { label: t('acc.entry'), value: <EntryLink id={e.journalEntryId} number={t('common.view')} /> } : null,
            e.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={e.postedAt} /> } : null,
            e.reversalReason ? { label: t('acc.reversalReason'), value: e.reversalReason } : null,
          ]}
        />
      }
      lines={
        <LinesTable<Schemas['ExpenseLine']>
          lines={e.lines ?? []}
          rowKey={(l) => l.id!}
          columns={[
            { id: 'no', header: '#', cell: (l) => l.lineNo },
            { id: 'account', header: t('acc.account'), cell: (l) => <EntityName source={entities.account} id={l.accountId} /> },
            { id: 'description', header: t('common.description'), hideBelow: 'md', cell: (l) => <Text value={l.description} /> },
            { id: 'tax', header: t('doc.tax'), hideBelow: 'sm', cell: (l) => <EntityName source={entities.taxCode} id={l.taxCodeId} /> },
            { id: 'net', header: t('doc.net'), align: 'right', cell: (l) => <Money value={l.netAmount} currency={e.currencyCode} showCurrency={false} /> },
            { id: 'total', header: t('doc.lineTotal'), align: 'right', cell: (l) => <Money value={l.totalAmount} currency={e.currencyCode} showCurrency={false} /> },
          ]}
        />
      }
      totals={<Totals currency={e.currencyCode} rows={[{ label: t('doc.subtotal'), value: e.subtotal }, { label: t('doc.taxTotal'), value: e.taxTotal }, { label: t('doc.grandTotal'), value: e.total, strong: true }]} />}
    >
      <FormDialog
        open={reversing}
        onOpenChange={setReversing}
        title={t('acc.reverse')}
        schema={z.object({ reversalDate: zf.date(), reason: zf.text(500) })}
        defaults={{ reversalDate: todayIso(), reason: '' }}
        destructive
        submitLabel={t('acc.reverse')}
        success={t('enums.REVERSED')}
        onSubmit={(v, key) => api.post('/expenses/{expenseId}/reverse', { expenseId: e.id! }, { body: { reversalDate: v.reversalDate!, reason: v.reason }, ifMatch: e.version, idempotencyKey: key })}
      >
        <DateField name="reversalDate" label={t('acc.reversalDate')} required />
        <TextField name="reason" label={t('acc.reversalReason')} required />
      </FormDialog>
    </DocumentLayout>
  );
}
