import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import Decimal from 'decimal.js';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useForm, useWatch, type Control } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { newIdempotencyKey, useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, DateTimeText, Money, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink, DocumentLayout, LinesTable } from '@/components/document/document-layout';
import { LineDecimal, LineEntity, LinesEditor, LineText, type LineColumn } from '@/components/document/lines-editor';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, DateField, EntityField, FieldGrid, Form, TextField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';
import { enums } from '@/lib/enums';
import { isDecimalString, todayIso } from '@/lib/format';
import { cn } from '@/lib/utils';

type Entry = Schemas['AccountingResponsesJournalEntry'];
type Line = Schemas['AccountingResponsesJournalLine'];

export function EntryLink({ id, number }: { id: string | undefined; number?: string | null }) {
  const { companyId } = useCompany();
  if (!id) return <Text value={null} />;
  return (
    <Link to="/c/$companyId/accounting/entries/$entryId" params={{ companyId, entryId: id }} data-row-link className="font-medium text-primary hover:underline">
      {number ?? t('doc.draft')}
    </Link>
  );
}

export function JournalEntriesPage() {
  const { can, companyId } = useCompany();
  const navigate = useNavigate();
  const create = can('accounting.journal_entry.create') ? (
    <Button onClick={() => void navigate({ to: '/c/$companyId/accounting/entries/new', params: { companyId } })}>
      <Plus aria-hidden />
      {t('acc.newEntry')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('acc.entriesTitle')} />
      <DataTable<Entry>
        id="journal-entries"
        fetchPage={(api, query, signal) => api.get('/journal-entries', null, { query, signal })}
        rowKey={(e) => e.id!}
        defaultSort="-createdAt"
        filters={[
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.journalEntryStatus] },
          { kind: 'enum', key: 'entryType', label: t('acc.entryType'), values: [...enums.journalEntryType] },
          { kind: 'entity', key: 'journalId', label: t('acc.journal'), source: entities.journal },
          { kind: 'dateRange', field: 'entryDate', label: t('acc.entryDate') },
        ]}
        columns={[
          { id: 'number', header: t('common.number'), cell: (e) => <EntryLink id={e.id} number={e.number} /> },
          { id: 'date', header: t('acc.entryDate'), sortKey: 'entryDate', cell: (e) => <DateText value={e.entryDate} /> },
          { id: 'description', header: t('common.description'), cell: (e) => <Text value={e.description} /> },
          { id: 'type', header: t('acc.entryType'), hideBelow: 'md', cell: (e) => enumLabel(e.entryType) },
          { id: 'source', header: t('acc.sourceDocument'), hideBelow: 'lg', cell: (e) => <Text value={e.sourceNumber} /> },
          { id: 'debit', header: t('acc.debit'), align: 'right', cell: (e) => <Money value={e.totalDebit} showCurrency={false} /> },
          { id: 'status', header: t('common.status'), cell: (e) => <StatusBadge status={e.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
    </div>
  );
}

const lineSchema = z.object({
  accountId: zf.id(),
  description: zf.optionalText(500),
  debit: zf.optionalDecimal(),
  credit: zf.optionalDecimal(),
  partnerId: zf.optionalId(),
  branchId: zf.optionalId(),
  departmentId: zf.optionalId(),
});
const entrySchema = z.object({
  journalId: zf.optionalId(),
  entryDate: zf.date(),
  description: zf.text(500),
  postImmediately: z.boolean(),
  lines: z.array(lineSchema).min(2, t('forms.required')),
});
type EntryValues = z.infer<typeof entrySchema>;
const emptyLine = (): EntryValues['lines'][number] => ({ accountId: null, description: '', debit: null, credit: null, partnerId: null, branchId: null, departmentId: null });

/** The entered debits and credits, summed for display only; balancing is the server's rule (UNBALANCED_ENTRY). */
function Balance({ control }: { control: Control<EntryValues> }) {
  const lines = useWatch({ control, name: 'lines' });
  const sum = (key: 'debit' | 'credit') => lines.reduce((s, l) => (isDecimalString(l[key]) ? s.plus(l[key]!) : s), new Decimal(0));
  const debit = sum('debit');
  const credit = sum('credit');
  const difference = debit.minus(credit);
  return (
    <dl className="ml-auto grid w-full max-w-sm grid-cols-3 gap-2 text-sm" aria-live="polite">
      <div><dt className="text-xs text-muted-foreground">{t('acc.debit')}</dt><dd><Money value={debit.toFixed()} showCurrency={false} /></dd></div>
      <div><dt className="text-xs text-muted-foreground">{t('acc.credit')}</dt><dd><Money value={credit.toFixed()} showCurrency={false} /></dd></div>
      <div>
        <dt className="text-xs text-muted-foreground">{t('acc.difference')}</dt>
        <dd className={cn(!difference.isZero() && 'font-semibold text-status-danger-fg')}><Money value={difference.toFixed()} showCurrency={false} /></dd>
      </div>
    </dl>
  );
}

function EntryEditor({ entry, onDone }: { entry?: Entry; onDone: (id: string) => void }) {
  const { api } = useCompany();
  const [key] = useState(newIdempotencyKey);
  const form = useForm<EntryValues>({
    resolver: zodResolver(entrySchema),
    defaultValues: {
      journalId: entry?.journalId ?? null,
      entryDate: entry?.entryDate ?? todayIso(),
      description: entry?.description ?? '',
      postImmediately: false,
      lines: entry?.lines?.length
        ? entry.lines.map((l) => ({
            accountId: l.accountId ?? null,
            description: l.description ?? '',
            debit: l.debit && l.debit !== '0' && !/^0(\.0+)?$/.test(l.debit) ? l.debit : null,
            credit: l.credit && !/^0(\.0+)?$/.test(l.credit) ? l.credit : null,
            partnerId: l.partnerId ?? null,
            branchId: l.branchId ?? null,
            departmentId: l.departmentId ?? null,
          }))
        : [emptyLine(), emptyLine()],
    },
  });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    const lines = v.lines.map((l) => ({
      accountId: l.accountId!,
      description: l.description || undefined,
      debit: l.debit || undefined,
      credit: l.credit || undefined,
      partnerId: l.partnerId ?? undefined,
      branchId: l.branchId ?? undefined,
      departmentId: l.departmentId ?? undefined,
    }));
    if (entry) {
      await api.patch('/journal-entries/{entryId}', { entryId: entry.id! }, {
        body: { journalId: v.journalId, entryDate: v.entryDate, description: v.description, lines },
        ifMatch: entry.version,
      });
      notify.success(t('common.saved'));
      onDone(entry.id!);
      return;
    }
    const created = (await api.post('/journal-entries', null, {
      body: { journalId: v.journalId ?? undefined, entryDate: v.entryDate!, description: v.description, postImmediately: v.postImmediately || undefined, lines },
      idempotencyKey: v.postImmediately ? key : undefined,
    })) as Entry;
    notify.success(v.postImmediately ? t('enums.POSTED') : t('doc.created'));
    onDone(created.id!);
  });
  const columns: LineColumn[] = [
    { key: 'account', header: t('acc.account'), className: 'min-w-64', render: (i) => <LineEntity name={`lines.${i}.accountId`} label={`${t('acc.account')} ${i + 1}`} source={entities.account} /> },
    { key: 'description', header: t('common.description'), className: 'min-w-40', render: (i) => <LineText name={`lines.${i}.description`} label={`${t('common.description')} ${i + 1}`} /> },
    { key: 'debit', header: t('acc.debit'), align: 'right', render: (i) => <LineDecimal name={`lines.${i}.debit`} label={`${t('acc.debit')} ${i + 1}`} /> },
    { key: 'credit', header: t('acc.credit'), align: 'right', render: (i) => <LineDecimal name={`lines.${i}.credit`} label={`${t('acc.credit')} ${i + 1}`} /> },
    { key: 'partner', header: t('acc.partner'), className: 'min-w-44', render: (i) => <LineEntity name={`lines.${i}.partnerId`} label={`${t('acc.partner')} ${i + 1}`} source={entities.partner} /> },
    { key: 'branch', header: t('common.branch'), className: 'min-w-36', render: (i) => <LineEntity name={`lines.${i}.branchId`} label={`${t('common.branch')} ${i + 1}`} source={entities.branch} /> },
    { key: 'department', header: t('common.department'), className: 'min-w-36', render: (i) => <LineEntity name={`lines.${i}.departmentId`} label={`${t('common.department')} ${i + 1}`} source={entities.department} /> },
  ];
  return (
    <Form form={form} onSubmit={submit}>
      <Section>
        <FieldGrid columns={3}>
          <EntityField name="journalId" label={t('acc.journal')} source={entities.journal} />
          <DateField name="entryDate" label={t('acc.entryDate')} required />
          <TextField name="description" label={t('common.description')} required />
        </FieldGrid>
      </Section>
      <Section title={t('common.lines')}>
        <LinesEditor<EntryValues> name="lines" columns={columns} newLine={emptyLine} minLines={2} />
        <div className="mt-4 border-t pt-3">
          <Balance control={form.control} />
        </div>
      </Section>
      {!entry ? <CheckboxField name="postImmediately" label={t('acc.postImmediately')} /> : null}
      <FormProblem {...problem} />
      <Button type="submit" disabled={form.formState.isSubmitting}>{t('doc.save')}</Button>
    </Form>
  );
}

export function NewJournalEntryPage() {
  const { companyId } = useCompany();
  const navigate = useNavigate();
  return (
    <div className="space-y-4">
      <PageHeader title={t('acc.newEntry')} breadcrumbs={<BackLink to={`/c/${companyId}/accounting/entries`} label={t('acc.entriesTitle')} />} />
      <EntryEditor onDone={(id) => void navigate({ to: '/c/$companyId/accounting/entries/$entryId', params: { companyId, entryId: id } })} />
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/accounting/entries/$entryId');
const reverseSchema = z.object({ reversalDate: zf.date(), reason: zf.text(500) });

export function JournalEntryPage() {
  const { entryId } = route.useParams();
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const query = useCompanyQuery(['journal-entries', entryId], (c, signal) => c.get('/journal-entries/{entryId}', { entryId }, { signal }));
  const [editing, setEditing] = useState(false);
  const [reversing, setReversing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const e = query.data;
  const manual = e.entryType === 'MANUAL' || e.entryType === 'ADJUSTMENT';
  const actions: DocAction<Entry>[] = [
    { id: 'edit', label: t('doc.edit'), when: inStatus('DRAFT'), permissions: ['accounting.journal_entry.create'], open: () => setEditing(true) },
    {
      id: 'post',
      label: t('doc.post'),
      primary: true,
      when: inStatus('DRAFT'),
      permissions: ['accounting.journal_entry.post'],
      confirm: { title: t('doc.postConfirm') },
      run: (c, x, ctx) => c.post('/journal-entries/{entryId}/post', { entryId: x.id! }, { ifMatch: x.version, idempotencyKey: ctx.idempotencyKey }),
      success: t('enums.POSTED'),
    },
    { id: 'reverse', label: t('acc.reverse'), variant: 'destructive', when: (x) => x.status === 'POSTED' && !x.reversedById && manual, permissions: ['accounting.journal_entry.reverse'], open: () => setReversing(true) },
    {
      id: 'delete',
      label: t('doc.deleteDraft'),
      variant: 'destructive',
      when: inStatus('DRAFT'),
      permissions: ['accounting.journal_entry.create'],
      confirm: { title: t('doc.deleteDraft'), destructive: true },
      run: (c, x) => c.delete('/journal-entries/{entryId}', { entryId: x.id! }, { ifMatch: x.version }),
      onSuccess: () => void navigate({ to: '/c/$companyId/accounting/entries', params: { companyId } }),
    },
  ];
  return (
    <DocumentLayout
      back={<BackLink to={`/c/${companyId}/accounting/entries`} label={t('acc.entriesTitle')} />}
      title={e.number ?? t('acc.newEntry')}
      badge={<StatusBadge status={e.status} />}
      description={e.description}
      actions={
        <>
          <AuditHistoryButton entityType="journal_entry" entityId={e.id} />
          <DocumentActions doc={e} actions={actions} />
        </>
      }
      summary={
        editing ? undefined : (
          <DetailList
            items={[
              { label: t('acc.entryDate'), value: <DateText value={e.entryDate} /> },
              { label: t('acc.journal'), value: <EntityName source={entities.journal} id={e.journalId} /> },
              { label: t('acc.entryType'), value: enumLabel(e.entryType) },
              { label: t('doc.currency'), value: e.currencyCode },
              e.sourceNumber ? { label: t('acc.sourceDocument'), value: `${enumLabel(e.sourceType)} ${e.sourceNumber}` } : null,
              e.reversalOfId ? { label: t('acc.reversalOf'), value: <EntryLink id={e.reversalOfId} number={t('common.view')} /> } : null,
              e.reversedById ? { label: t('acc.reversedBy'), value: <EntryLink id={e.reversedById} number={t('common.view')} /> } : null,
              e.postedAt ? { label: t('inv.postedAt'), value: <DateTimeText value={e.postedAt} /> } : null,
            ]}
          />
        )
      }
      lines={
        editing ? undefined : (
          <LinesTable<Line>
            lines={e.lines ?? []}
            rowKey={(l) => l.id!}
            columns={[
              { id: 'no', header: '#', cell: (l) => l.lineNo },
              { id: 'account', header: t('acc.account'), cell: (l) => <EntityName source={entities.account} id={l.accountId} /> },
              { id: 'description', header: t('common.description'), hideBelow: 'md', cell: (l) => <Text value={l.description} /> },
              { id: 'partner', header: t('acc.partner'), hideBelow: 'lg', cell: (l) => <EntityName source={entities.partner} id={l.partnerId} fallback="" /> },
              { id: 'debit', header: t('acc.debit'), align: 'right', cell: (l) => <Money value={l.debit} showCurrency={false} />, footer: <Money value={e.totalDebit} showCurrency={false} /> },
              { id: 'credit', header: t('acc.credit'), align: 'right', cell: (l) => <Money value={l.credit} showCurrency={false} />, footer: <Money value={e.totalCredit} showCurrency={false} /> },
            ]}
          />
        )
      }
    >
      {editing ? (
        <div className="space-y-2">
          <Button variant="ghost" onClick={() => setEditing(false)}>{t('common.cancel')}</Button>
          <EntryEditor entry={e} onDone={() => setEditing(false)} />
        </div>
      ) : null}
      <FormDialog
        open={reversing}
        onOpenChange={setReversing}
        title={t('acc.reverse')}
        schema={reverseSchema}
        defaults={{ reversalDate: todayIso(), reason: '' }}
        destructive
        submitLabel={t('acc.reverse')}
        onSubmit={(v, key) => api.post('/journal-entries/{entryId}/reverse', { entryId: e.id! }, { body: { reversalDate: v.reversalDate!, reason: v.reason }, ifMatch: e.version, idempotencyKey: key })}
        onDone={(r) => {
          const reversal = r as Entry | undefined;
          if (reversal?.id) void navigate({ to: '/c/$companyId/accounting/entries/$entryId', params: { companyId, entryId: reversal.id } });
        }}
      >
        <DateField name="reversalDate" label={t('acc.reversalDate')} required />
        <TextField name="reason" label={t('acc.reversalReason')} required />
      </FormDialog>
    </DocumentLayout>
  );
}
