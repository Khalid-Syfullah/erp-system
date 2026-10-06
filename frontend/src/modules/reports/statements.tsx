// Accounting's financial statements (API.md §17.8): their responses are statements, not report rows,
// so each has its own layout. Amounts are at the ledger scale and in the base currency.
import type { ReactNode } from 'react';
import type { Schemas } from '@/api/client';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, Money, Text } from '@/components/common/values';
import { EntryLink } from '@/modules/accounting/entries';
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { t } from '@/i18n';
import { cn } from '@/lib/utils';

export const STATEMENTS = new Set(['trial-balance', 'general-ledger', 'profit-and-loss', 'balance-sheet', 'ar-ageing', 'ap-ageing', 'cash-book']);

function Amount({ value, strong }: { value: string | null | undefined; strong?: boolean }) {
  return <Money value={value} showCurrency={false} className={cn(strong && 'font-semibold')} />;
}

function AccountCell({ account }: { account?: Schemas['AccountRef'] }) {
  return (
    <span>
      <span className="font-mono text-xs">{account?.code}</span> {account?.name}
    </span>
  );
}

function StatementTable({ caption, head, children, foot }: { caption: string; head: ReactNode; children: ReactNode; foot?: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <caption className="sr-only">{caption}</caption>
        <TableHeader>{head}</TableHeader>
        <TableBody>{children}</TableBody>
        {foot ? <TableFooter>{foot}</TableFooter> : null}
      </Table>
    </div>
  );
}

function TrialBalanceView({ data }: { data: Schemas['TrialBalance'] }) {
  return (
    <StatementTable
      caption="Trial balance"
      head={
        <TableRow>
          <TableHead>{t('acc.account')}</TableHead>
          <TableHead className="text-right">{t('rep.statement.opening')}</TableHead>
          <TableHead className="text-right">{t('rep.statement.debit')}</TableHead>
          <TableHead className="text-right">{t('rep.statement.credit')}</TableHead>
          <TableHead className="text-right">{t('rep.statement.closing')}</TableHead>
        </TableRow>
      }
      foot={
        <TableRow>
          <TableCell>{t('rep.statement.total')}</TableCell>
          <TableCell />
          <TableCell className="text-right"><Amount value={data.totalDebit} strong /></TableCell>
          <TableCell className="text-right"><Amount value={data.totalCredit} strong /></TableCell>
          <TableCell />
        </TableRow>
      }
    >
      {(data.rows ?? []).map((r) => (
        <TableRow key={r.account?.id}>
          <TableCell><AccountCell account={r.account} /></TableCell>
          <TableCell className="text-right"><Amount value={r.opening} /></TableCell>
          <TableCell className="text-right"><Amount value={r.debit} /></TableCell>
          <TableCell className="text-right"><Amount value={r.credit} /></TableCell>
          <TableCell className="text-right"><Amount value={r.closing} /></TableCell>
        </TableRow>
      ))}
    </StatementTable>
  );
}

function LedgerView({ opening, closing, rows, caption }: { opening?: string; closing?: string; rows: Schemas['LedgerRow'][] | Schemas['BankTransaction'][]; caption: string }) {
  return (
    <StatementTable
      caption={caption}
      head={
        <TableRow>
          <TableHead>{t('common.date')}</TableHead>
          <TableHead>{t('acc.entry')}</TableHead>
          <TableHead className="hidden md:table-cell">{t('common.description')}</TableHead>
          <TableHead className="text-right">{t('rep.statement.debit')}</TableHead>
          <TableHead className="text-right">{t('rep.statement.credit')}</TableHead>
          <TableHead className="text-right">{t('rep.statement.balance')}</TableHead>
        </TableRow>
      }
      foot={
        <TableRow>
          <TableCell colSpan={5}>{t('rep.statement.closing')}</TableCell>
          <TableCell className="text-right"><Amount value={closing} strong /></TableCell>
        </TableRow>
      }
    >
      <TableRow>
        <TableCell colSpan={5} className="text-muted-foreground">{t('rep.statement.opening')}</TableCell>
        <TableCell className="text-right"><Amount value={opening} /></TableCell>
      </TableRow>
      {rows.map((r) => (
        <TableRow key={r.journalLineId}>
          <TableCell><DateText value={r.entryDate} /></TableCell>
          <TableCell>
            <EntryLink id={r.journalEntryId} number={r.entryNumber} />
            {r.sourceNumber ? <div className="text-xs text-muted-foreground">{r.sourceNumber}</div> : null}
          </TableCell>
          <TableCell className="hidden md:table-cell"><Text value={r.description} /></TableCell>
          <TableCell className="text-right"><Amount value={r.debit} /></TableCell>
          <TableCell className="text-right"><Amount value={r.credit} /></TableCell>
          <TableCell className="text-right"><Amount value={(r as { balance?: string }).balance ?? (r as { runningBalance?: string }).runningBalance} /></TableCell>
        </TableRow>
      ))}
    </StatementTable>
  );
}

function SectionRows({ title, lines, total, totalLabel, comparison }: { title: string; lines: Schemas['StatementLine'][]; total?: string; totalLabel: string; comparison: boolean }) {
  return (
    <>
      <TableRow className="bg-muted/40">
        <TableCell colSpan={comparison ? 3 : 2} className="font-semibold">{title}</TableCell>
      </TableRow>
      {lines.map((l) => (
        <TableRow key={l.account?.id}>
          <TableCell className="pl-6"><AccountCell account={l.account} /></TableCell>
          <TableCell className="text-right"><Amount value={l.amount} /></TableCell>
          {comparison ? <TableCell className="text-right"><Amount value={l.comparison} /></TableCell> : null}
        </TableRow>
      ))}
      <TableRow>
        <TableCell className="font-medium">{totalLabel}</TableCell>
        <TableCell className="text-right"><Amount value={total} strong /></TableCell>
        {comparison ? <TableCell /> : null}
      </TableRow>
    </>
  );
}

function ProfitAndLossView({ data }: { data: Schemas['ProfitAndLoss'] }) {
  const comparison = !!data.compareFrom;
  return (
    <StatementTable
      caption="Profit and loss"
      head={
        <TableRow>
          <TableHead>{t('acc.account')}</TableHead>
          <TableHead className="text-right"><DateText value={data.from} /> – <DateText value={data.to} /></TableHead>
          {comparison ? <TableHead className="text-right">{t('rep.statement.comparison')}</TableHead> : null}
        </TableRow>
      }
      foot={
        <TableRow>
          <TableCell>{t('rep.statement.netProfit')}</TableCell>
          <TableCell className="text-right"><Amount value={data.netProfit} strong /></TableCell>
          {comparison ? <TableCell className="text-right"><Amount value={data.comparisonNetProfit} strong /></TableCell> : null}
        </TableRow>
      }
    >
      <SectionRows title={t('rep.statement.revenue')} lines={data.revenue ?? []} total={data.totalRevenue} totalLabel={t('rep.statement.totalRevenue')} comparison={comparison} />
      <SectionRows title={t('rep.statement.expenses')} lines={data.expenses ?? []} total={data.totalExpenses} totalLabel={t('rep.statement.totalExpenses')} comparison={comparison} />
    </StatementTable>
  );
}

function BalanceSheetView({ data }: { data: Schemas['BalanceSheet'] }) {
  return (
    <div className="space-y-3">
      <StatusBadge status={data.balanced ? 'BALANCED' : 'FAILED'} label={data.balanced ? t('rep.statement.balanced') : t('rep.statement.notBalanced')} />
      <StatementTable
        caption="Balance sheet"
        head={
          <TableRow>
            <TableHead>{t('acc.account')}</TableHead>
            <TableHead className="text-right"><DateText value={data.asOf} /></TableHead>
          </TableRow>
        }
      >
        <SectionRows title={t('rep.statement.assets')} lines={data.assets ?? []} total={data.totalAssets} totalLabel={t('rep.statement.totalAssets')} comparison={false} />
        <SectionRows title={t('rep.statement.liabilities')} lines={data.liabilities ?? []} total={data.totalLiabilities} totalLabel={t('rep.statement.totalLiabilities')} comparison={false} />
        <SectionRows title={t('rep.statement.equity')} lines={data.equity ?? []} total={data.totalEquity} totalLabel={t('rep.statement.totalEquity')} comparison={false} />
        <TableRow>
          <TableCell className="pl-6">{t('rep.statement.currentEarnings')}</TableCell>
          <TableCell className="text-right"><Amount value={data.currentEarnings} /></TableCell>
        </TableRow>
      </StatementTable>
    </div>
  );
}

const buckets = ['current', 'days1To30', 'days31To60', 'days61To90', 'over90', 'total'] as const;

function AgeingView({ data }: { data: Schemas['Ageing'] }) {
  return (
    <StatementTable
      caption="Ageing"
      head={
        <TableRow>
          <TableHead>{t('acc.partner')}</TableHead>
          {buckets.map((b) => (
            <TableHead key={b} className="text-right">{t(`rep.statement.${b}`)}</TableHead>
          ))}
        </TableRow>
      }
      foot={
        <TableRow>
          <TableCell>{t('rep.statement.total')}</TableCell>
          {buckets.map((b) => (
            <TableCell key={b} className="text-right"><Amount value={data.total?.[b]} strong /></TableCell>
          ))}
        </TableRow>
      }
    >
      {(data.partners ?? []).map((p) => (
        <TableRow key={p.partnerId}>
          <TableCell>
            <span className="font-mono text-xs">{p.partnerCode}</span> {p.partnerName}
          </TableCell>
          {buckets.map((b) => (
            <TableCell key={b} className="text-right"><Amount value={p.buckets?.[b]} strong={b === 'total'} /></TableCell>
          ))}
        </TableRow>
      ))}
    </StatementTable>
  );
}

/** Renders one of Accounting's statements by report code. */
export function StatementView({ code, data }: { code: string; data: unknown }) {
  switch (code) {
    case 'trial-balance':
      return <TrialBalanceView data={data as Schemas['TrialBalance']} />;
    case 'general-ledger': {
      const gl = data as Schemas['GeneralLedger'];
      return (
        <div className="space-y-2">
          <p className="text-sm font-medium"><AccountCell account={gl.account} /></p>
          <LedgerView opening={gl.opening} closing={gl.closing} rows={gl.rows ?? []} caption="General ledger" />
        </div>
      );
    }
    case 'cash-book': {
      const book = data as Schemas['CashBook'];
      return <LedgerView opening={book.opening} closing={book.closing} rows={book.transactions ?? []} caption="Cash book" />;
    }
    case 'profit-and-loss':
      return <ProfitAndLossView data={data as Schemas['ProfitAndLoss']} />;
    case 'balance-sheet':
      return <BalanceSheetView data={data as Schemas['BalanceSheet']} />;
    case 'ar-ageing':
    case 'ap-ageing':
      return <AgeingView data={data as Schemas['Ageing']} />;
    default:
      return null;
  }
}
