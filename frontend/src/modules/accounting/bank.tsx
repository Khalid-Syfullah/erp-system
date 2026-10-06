import { getRouteApi, Link } from '@tanstack/react-router';
import { useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { PageHeader, Section } from '@/components/common/page';
import { BooleanBadge, StatusBadge } from '@/components/common/status-badge';
import { DateText, Money, Text } from '@/components/common/values';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, EntityField, FieldGrid, TextField } from '@/components/form/fields';
import { DateInput } from '@/components/form/inputs';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { MasterDataPage } from '@/components/master/master-data-page';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, t } from '@/i18n';
import { startOfMonthIso, todayIso } from '@/lib/format';
import { EntryLink } from './entries';

type BankAccount = Schemas['AccountingResponsesBankAccount'];

const schema = z.object({
  name: zf.text(100),
  accountId: zf.id(),
  currencyCode: zf.id(),
  bankName: zf.optionalText(100),
  accountNumber: zf.optionalText(40),
  iban: zf.optionalText(40),
});

export function BankAccountsPage() {
  const { companyId } = useCompany();
  return (
    <MasterDataPage<BankAccount, z.infer<typeof schema>>
      title={t('acc.bankAccountsTitle')}
      managePermission="accounting.bank_account.manage"
      table={{
        id: 'bank-accounts',
        fetchPage: (api, query, signal) => api.get('/bank-accounts', null, { query, signal }),
        rowKey: (b) => b.id!,
        defaultSort: 'name',
        searchable: false,
        columns: [
          {
            id: 'name',
            header: t('common.name'),
            sortKey: 'name',
            cell: (b) => (
              <Link to="/c/$companyId/accounting/bank-accounts/$bankAccountId" params={{ companyId, bankAccountId: b.id! }} data-row-link className="font-medium text-primary hover:underline">
                {b.name}
              </Link>
            ),
          },
          { id: 'bank', header: t('fields.bankName'), hideBelow: 'sm', cell: (b) => <Text value={b.bankName} /> },
          { id: 'number', header: t('fields.accountNumber'), hideBelow: 'md', cell: (b) => <span className="font-mono text-xs">{b.accountNumberMasked ?? '—'}</span> },
          { id: 'gl', header: t('acc.glAccount'), cell: (b) => <EntityName source={entities.account} id={b.accountId} /> },
          { id: 'currency', header: t('common.currency'), cell: (b) => b.currencyCode },
          { id: 'active', header: t('common.status'), cell: (b) => <BooleanBadge value={b.isActive !== false} yes={t('common.active')} no={t('common.inactive')} /> },
        ],
      }}
      form={{
        schema,
        values: (b) => ({ name: b?.name ?? '', accountId: b?.accountId ?? null, currencyCode: b?.currencyCode ?? null, bankName: b?.bankName ?? '', accountNumber: '', iban: '' }),
        createTitle: t('acc.newBankAccount'),
        editTitle: () => t('acc.editBankAccount'),
        fields: (mode) => (
          <>
            <TextField name="name" label={t('common.name')} required />
            <FieldGrid>
              <EntityField name="accountId" label={t('acc.glAccount')} source={entities.account} filter={(a) => a.accountSubtype === 'BANK' || a.accountSubtype === 'CASH'} required disabled={mode === 'edit'} />
              <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} required disabled={mode === 'edit'} />
              <TextField name="bankName" label={t('fields.bankName')} />
              {mode === 'create' ? <TextField name="accountNumber" label={t('fields.accountNumber')} /> : null}
              {mode === 'create' ? <TextField name="iban" label={t('fields.iban')} /> : null}
            </FieldGrid>
          </>
        ),
        create: (api, v) => api.post('/bank-accounts', null, { body: compact(v) as Schemas['CompanyBankAccountBankAccountRequest'] }),
        update: (api, b, v) =>
          api.patch('/bank-accounts/{bankAccountId}', { bankAccountId: b.id! }, { body: mergePatch({ name: b.name, bankName: b.bankName }, { name: v.name, bankName: v.bankName }), ifMatch: b.version }),
      }}
      rowActions={[
        {
          id: 'deactivate',
          label: t('common.deactivate'),
          when: (b) => b.isActive !== false,
          permissions: ['accounting.bank_account.manage'],
          run: (api, b) => api.patch('/bank-accounts/{bankAccountId}', { bankAccountId: b.id! }, { body: { isActive: false }, ifMatch: b.version }),
        },
        {
          id: 'activate',
          label: t('common.activate'),
          when: (b) => b.isActive === false,
          permissions: ['accounting.bank_account.manage'],
          run: (api, b) => api.patch('/bank-accounts/{bankAccountId}', { bankAccountId: b.id! }, { body: { isActive: true }, ifMatch: b.version }),
        },
      ]}
    />
  );
}

const route = getRouteApi('/_authed/c/$companyId/accounting/bank-accounts/$bankAccountId');

/** Posted bank transactions with the running balance, and manual reconciliation marks (§8.8). */
export function BankAccountPage() {
  const { bankAccountId } = route.useParams();
  const { api, can } = useCompany();
  const [from, setFrom] = useState(startOfMonthIso());
  const [to, setTo] = useState(todayIso());
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [marking, setMarking] = useState(false);
  const [unmarking, setUnmarking] = useState(false);
  const book = useCompanyQuery(['bank-accounts', bankAccountId, 'transactions', from, to], (c, signal) =>
    c.get('/bank-accounts/{bankAccountId}/transactions', { bankAccountId }, { query: { from, to }, signal }),
  );
  const reconcile = can('accounting.bank_reconciliation.manage');
  const toggle = (id: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const b = book.data?.bankAccount;
  const lines = book.data?.transactions ?? [];
  return (
    <div className="space-y-4">
      <PageHeader
        title={b?.name ?? t('acc.bankAccountsTitle')}
        description={b ? `${b.bankName ?? ''} …${b.accountNumberLast4 ?? ''} · ${b.currencyCode}` : undefined}
        actions={
          reconcile ? (
            <>
              <Button variant="outline" disabled={selected.size === 0} onClick={() => setUnmarking(true)}>{t('acc.unreconcile')}</Button>
              <Button disabled={selected.size === 0} onClick={() => setMarking(true)}>{t('acc.reconcile')}</Button>
            </>
          ) : null
        }
      />
      <Section>
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1.5">
            <Label htmlFor="bank-from">{t('common.from')}</Label>
            <DateInput id="bank-from" value={from} onChange={(v) => setFrom(v ?? startOfMonthIso())} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="bank-to">{t('common.to')}</Label>
            <DateInput id="bank-to" value={to} onChange={(v) => setTo(v ?? todayIso())} />
          </div>
          {selected.size > 0 ? <span className="text-sm text-muted-foreground">{t('acc.selected', { count: selected.size })}</span> : null}
        </div>
      </Section>
      <Section bodyClassName="p-0" title={t('acc.transactions')}>
        {book.isLoading ? <LoadingState /> : book.isError ? <ErrorState error={book.error} onRetry={() => book.refetch()} /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  {reconcile ? <TableHead className="w-10"><span className="sr-only">{t('common.select')}</span></TableHead> : null}
                  <TableHead>{t('common.date')}</TableHead>
                  <TableHead>{t('acc.entry')}</TableHead>
                  <TableHead className="hidden md:table-cell">{t('common.description')}</TableHead>
                  <TableHead className="text-right">{t('acc.debit')}</TableHead>
                  <TableHead className="text-right">{t('acc.credit')}</TableHead>
                  <TableHead className="text-right">{t('acc.runningBalance')}</TableHead>
                  <TableHead>{t('acc.reconciled')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow>
                  {reconcile ? <TableCell /> : null}
                  <TableCell colSpan={5} className="text-muted-foreground">{t('acc.opening')}</TableCell>
                  <TableCell className="text-right"><Money value={book.data?.opening} showCurrency={false} /></TableCell>
                  <TableCell />
                </TableRow>
                {lines.length === 0 ? (
                  <TableRow><TableCell colSpan={8}><EmptyState className="py-4" /></TableCell></TableRow>
                ) : (
                  lines.map((l) => (
                    <TableRow key={l.journalLineId} data-state={selected.has(l.journalLineId!) ? 'selected' : undefined}>
                      {reconcile ? (
                        <TableCell>
                          <Checkbox checked={selected.has(l.journalLineId!)} onCheckedChange={() => toggle(l.journalLineId!)} aria-label={`${t('common.select')} ${l.entryNumber}`} />
                        </TableCell>
                      ) : null}
                      <TableCell><DateText value={l.entryDate} /></TableCell>
                      <TableCell>
                        <EntryLink id={l.journalEntryId} number={l.entryNumber} />
                        {l.sourceNumber ? <div className="text-xs text-muted-foreground">{enumLabel(l.sourceType)} {l.sourceNumber}</div> : null}
                      </TableCell>
                      <TableCell className="hidden md:table-cell"><Text value={l.description} /></TableCell>
                      <TableCell className="text-right"><Money value={l.debit} showCurrency={false} /></TableCell>
                      <TableCell className="text-right"><Money value={l.credit} showCurrency={false} /></TableCell>
                      <TableCell className="text-right"><Money value={l.runningBalance} showCurrency={false} /></TableCell>
                      <TableCell>{l.statementReference ? <StatusBadge status="ACTIVE" label={`${l.statementReference}`} /> : null}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
              <TableFooter>
                <TableRow>
                  {reconcile ? <TableCell /> : null}
                  <TableCell colSpan={5}>{t('acc.closing')}</TableCell>
                  <TableCell className="text-right"><Money value={book.data?.closing} showCurrency={false} /></TableCell>
                  <TableCell />
                </TableRow>
              </TableFooter>
            </Table>
          </div>
        )}
      </Section>
      <FormDialog
        open={marking}
        onOpenChange={setMarking}
        title={t('acc.reconcile')}
        schema={z.object({ statementReference: zf.text(100), statementDate: zf.date() })}
        defaults={{ statementReference: '', statementDate: to }}
        onSubmit={(v) =>
          api.post('/bank-reconciliation-marks', null, { body: { journalLineIds: [...selected], statementReference: v.statementReference, statementDate: v.statementDate! } })
        }
        onDone={() => {
          setSelected(new Set());
          notify.success(t('common.saved'));
        }}
      >
        <TextField name="statementReference" label={t('acc.statementReference')} required />
        <DateField name="statementDate" label={t('acc.statementDate')} required />
      </FormDialog>
      <ConfirmDialog
        open={unmarking}
        onOpenChange={setUnmarking}
        title={t('acc.unreconcile')}
        confirmLabel={t('acc.unreconcile')}
        onConfirm={async () => {
          await api.delete('/bank-reconciliation-marks', null, { body: { journalLineIds: [...selected] } });
          setSelected(new Set());
          await book.refetch();
        }}
      />
    </div>
  );
}
