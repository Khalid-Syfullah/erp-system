import { useQuery } from '@tanstack/react-query';
import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight, Download, Play, Save } from 'lucide-react';
import { useMemo, useState, type FormEvent } from 'react';
import { useController, useFormContext } from 'react-hook-form';
import { z } from 'zod';
import { request, type Query, type Schemas } from '@/api/client';
import { companyKey, newIdempotencyKey, useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { BarChart } from '@/components/common/bar-chart';
import { PageHeader, Section } from '@/components/common/page';
import { DateText, Money, Percent, Quantity, Text } from '@/components/common/values';
import { EntityPicker } from '@/components/data/entity';
import { entities, type EntitySource } from '@/components/data/entities';
import { BackLink } from '@/components/document/document-layout';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, TextField } from '@/components/form/fields';
import { DateInput } from '@/components/form/inputs';
import { zf } from '@/components/form/schema';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, serverText, t, tryT } from '@/i18n';
import { compareDecimals, formatDateTime, formatDecimal, formatMoney, startOfMonthIso, todayIso } from '@/lib/format';
import { cn } from '@/lib/utils';
import { STATEMENTS, StatementView } from './statements';

type Report = Schemas['Report'];
type Column = Schemas['Column'];
type Parameter = Schemas['Parameter'];
type Values = Record<string, string | null>;

const route = getRouteApi('/_authed/c/$companyId/reports/$reportCode');

/** The parameters of ID type, resolved with the matching reference data source. */
const idSources: Record<string, EntitySource<unknown>> = {
  branchId: entities.branch as EntitySource<unknown>,
  departmentId: entities.department as EntitySource<unknown>,
  warehouseId: entities.warehouse as EntitySource<unknown>,
  customerId: entities.customer as EntitySource<unknown>,
  supplierId: entities.supplier as EntitySource<unknown>,
  partnerId: entities.partner as EntitySource<unknown>,
  productId: entities.product as EntitySource<unknown>,
  categoryId: entities.category as EntitySource<unknown>,
  accountId: entities.account as EntitySource<unknown>,
  bankAccountId: entities.bankAccount as EntitySource<unknown>,
  employeeId: entities.employee as EntitySource<unknown>,
  leaveTypeId: entities.leaveType as EntitySource<unknown>,
  reasonCodeId: entities.reasonCode as EntitySource<unknown>,
};

export function useReportCatalogue() {
  return useCompanyQuery(['reports', 'catalogue'], (api, signal) => api.get('/reports', null, { signal }), { staleTime: 5 * 60_000 });
}

function defaults(report: Report, search: Record<string, unknown>): Values {
  const values: Values = {};
  for (const p of report.parameters ?? []) {
    const fromUrl = search[p.name!];
    if (typeof fromUrl === 'string' && fromUrl !== '') values[p.name!] = fromUrl;
    else if (p.defaultValue) values[p.name!] = p.defaultValue;
    else if (p.required && p.type === 'DATE') values[p.name!] = p.name === 'from' ? startOfMonthIso() : todayIso();
    else values[p.name!] = null;
  }
  return values;
}

function ParameterInput({ parameter, value, onChange }: { parameter: Parameter; value: string | null; onChange: (value: string | null) => void }) {
  const id = `param-${parameter.name}`;
  const label = (
    <Label htmlFor={id} id={`${id}-label`}>
      {serverText(parameter.description) || parameter.name}
      {parameter.required ? <span className="text-destructive" aria-hidden> *</span> : null}
    </Label>
  );
  switch (parameter.type) {
    case 'DATE':
      return <div className="space-y-1.5">{label}<DateInput id={id} value={value} onChange={onChange} required={parameter.required} /></div>;
    case 'BOOLEAN':
      return (
        <div className="flex items-center gap-2 self-end pb-2">
          <Checkbox id={id} checked={value === 'true'} onCheckedChange={(c) => onChange(c === true ? 'true' : 'false')} />
          {label}
        </div>
      );
    case 'ENUM':
      return (
        <div className="space-y-1.5">
          {label}
          <Select value={value ?? '__any__'} onValueChange={(v) => onChange(v === '__any__' ? null : v)}>
            <SelectTrigger id={id} className="w-full"><SelectValue /></SelectTrigger>
            <SelectContent>
              {!parameter.required ? <SelectItem value="__any__">{t('common.all')}</SelectItem> : null}
              {(parameter.values ?? []).map((v) => <SelectItem key={v} value={v}>{enumLabel(v)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      );
    case 'ID': {
      const source = idSources[parameter.name!];
      return (
        <div className="space-y-1.5">
          {label}
          {source ? (
            <EntityPicker id={id} source={source} value={value} onChange={onChange} aria-labelledby={`${id}-label`} />
          ) : (
            <Input id={id} value={value ?? ''} onChange={(e) => onChange(e.target.value || null)} />
          )}
        </div>
      );
    }
    default:
      return (
        <div className="space-y-1.5">
          {label}
          <Input id={id} inputMode={parameter.type === 'INTEGER' ? 'numeric' : undefined} value={value ?? ''} onChange={(e) => onChange(e.target.value || null)} />
        </div>
      );
  }
}

function Cell({ column, value }: { column: Column; value: unknown }) {
  if (value === null || value === undefined || value === '') return <Text value={null} />;
  switch (column.type) {
    case 'AMOUNT':
      return <Money value={String(value)} showCurrency={false} />;
    case 'QUANTITY':
    case 'DECIMAL':
      return <Quantity value={String(value)} />;
    case 'PERCENT':
      return <Percent value={String(value)} />;
    case 'DATE':
      return <DateText value={String(value)} />;
    case 'BOOLEAN':
      return <>{value ? t('common.yes') : t('common.no')}</>;
    case 'INTEGER':
      return <span className="tabular">{formatDecimal(String(value))}</span>;
    default:
      // Enumeration values get their label; any other text (codes, SKUs, names) is shown as it is.
      return <>{(typeof value === 'string' && /^[A-Z][A-Z_]+$/.test(value) ? tryT(`enums.${value}`) : undefined) ?? String(value)}</>;
  }
}

const numeric = (c: Column) => ['AMOUNT', 'QUANTITY', 'DECIMAL', 'PERCENT', 'INTEGER'].includes(c.type ?? '');

/**
 * Runs a report from the catalogue (API.md §17.11): parameters from the report's metadata, rows with
 * server-side sorting and keyset paging, totals over all rows, chart, export and saving.
 */
export function ReportRunPage() {
  const { reportCode } = route.useParams();
  const search = route.useSearch() as Record<string, unknown>;
  const catalogue = useReportCatalogue();
  const report = catalogue.data?.find((r) => r.code === reportCode);
  if (catalogue.isLoading) return <LoadingState />;
  if (catalogue.isError) return <ErrorState error={catalogue.error} />;
  if (!report) return <ErrorState error={{ status: 404 }} />;
  return <ReportRunner key={report.code} report={report} search={search} />;
}

function ReportRunner({ report, search }: { report: Report; search: Record<string, unknown> }) {
  const { companyId, can } = useCompany();
  const navigate = useNavigate();
  const [values, setValues] = useState<Values>(() => defaults(report, search));
  const [submitted, setSubmitted] = useState<Values | null>(() => (search.run === '1' ? defaults(report, search) : null));
  const [sort, setSort] = useState<string | null>(null);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [exporting, setExporting] = useState(false);
  const [saving, setSaving] = useState(false);
  const statement = STATEMENTS.has(report.code!);
  const cursor = cursors[cursors.length - 1] ?? null;

  const query = useQuery({
    queryKey: companyKey(companyId, 'report-run', report.code, submitted, sort, cursor),
    enabled: submitted !== null,
    queryFn: ({ signal }) => {
      const q: Query = { ...Object.fromEntries(Object.entries(submitted ?? {}).filter(([, v]) => v !== null && v !== '')) };
      if (!statement) {
        if (sort) q.sort = sort;
        if (cursor) q.cursor = cursor;
      }
      return request<unknown>('GET', report.path!, { query: q, signal });
    },
    placeholderData: (previous) => previous,
    retry: false,
  });

  const run = (event: FormEvent) => {
    event.preventDefault();
    setCursors([null]);
    setSubmitted({ ...values });
    void navigate({
      to: '/c/$companyId/reports/$reportCode',
      params: { companyId, reportCode: report.code! },
      search: { ...Object.fromEntries(Object.entries(values).filter(([, v]) => v)), run: '1' } as never,
      replace: true,
    });
  };

  const result = query.data as Schemas['ReportRun'] | undefined;
  const columns = (result?.columns ?? report.columns ?? []).filter((c) => c.type !== 'ID');
  const rows = useMemo(() => (result?.data ?? []) as Record<string, unknown>[], [result]);
  const totals = (result?.totals ?? null) as Record<string, unknown> | null;
  const currentSort = sort ? { field: sort.replace(/^-/, ''), descending: sort.startsWith('-') } : null;

  const chart = useMemo(() => {
    if (statement || rows.length < 2) return null;
    const category = columns.find((c) => c.type === 'TEXT');
    const measure = columns.find((c) => c.type === 'AMOUNT' && c.total) ?? columns.find((c) => c.type === 'AMOUNT');
    if (!category || !measure) return null;
    const data = rows
      .filter((r) => typeof r[measure.key!] === 'string')
      .map((r) => ({ label: String(r[category.key!] ?? ''), value: String(r[measure.key!]) }))
      .sort((a, b) => compareDecimals(b.value, a.value))
      .slice(0, 10);
    return { data, title: t('rep.chartLabel', { value: serverText(measure.label), category: serverText(category.label), count: data.length }) };
  }, [rows, columns, statement]);

  const parameters = Object.fromEntries(Object.entries(submitted ?? values).filter(([, v]) => v !== null && v !== '')) as Record<string, string>;
  return (
    <div className="space-y-4">
      <PageHeader
        breadcrumbs={<BackLink to={`/c/${companyId}/reports`} label={t('rep.centreTitle')} />}
        title={serverText(report.name)}
        description={serverText(report.description)}
        actions={
          <>
            <Button variant="outline" onClick={() => setSaving(true)}>
              <Save aria-hidden />
              {t('rep.save')}
            </Button>
            {can('reporting.export.create') && (report.exportFormats ?? []).length > 0 ? (
              <Button variant="outline" onClick={() => setExporting(true)}>
                <Download aria-hidden />
                {t('rep.export')}
              </Button>
            ) : null}
          </>
        }
      />
      <Section title={t('rep.parameters')}>
        <form onSubmit={run} className="space-y-4" noValidate>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {(report.parameters ?? []).map((p) => (
              <ParameterInput key={p.name} parameter={p} value={values[p.name!] ?? null} onChange={(v) => setValues((x) => ({ ...x, [p.name!]: v }))} />
            ))}
          </div>
          <Button type="submit" disabled={query.isFetching}>
            <Play aria-hidden />
            {query.isFetching ? t('rep.running') : t('rep.run')}
          </Button>
        </form>
      </Section>
      {submitted === null ? (
        <EmptyState title={t('rep.noRun')} />
      ) : query.isError ? (
        <ErrorState error={query.error} />
      ) : query.isLoading ? (
        <LoadingState />
      ) : statement ? (
        <Section bodyClassName="p-0">
          <StatementView code={report.code!} data={query.data} />
        </Section>
      ) : (
        <>
          {chart && chart.data.length > 1 ? (
            <Section>
              <BarChart data={chart.data} title={chart.title} format={(v) => formatMoney(v, null)} />
            </Section>
          ) : null}
          <Section
            bodyClassName="p-0"
            title={result?.generatedAt ? t('rep.generatedAt', { time: formatDateTime(result.generatedAt) }) : undefined}
          >
            {rows.length === 0 ? (
              <EmptyState title={t('rep.noData')} />
            ) : (
              <div className="overflow-x-auto" aria-busy={query.isFetching || undefined}>
                <Table>
                  <caption className="sr-only">{serverText(report.name)}</caption>
                  <TableHeader>
                    <TableRow>
                      {columns.map((c) => {
                        const sorted = currentSort?.field === c.key ? currentSort : null;
                        return (
                          <TableHead key={c.key} scope="col" aria-sort={sorted ? (sorted.descending ? 'descending' : 'ascending') : undefined} className={cn(numeric(c) && 'text-right')}>
                            {c.sortable ? (
                              <button
                                type="button"
                                className={cn('inline-flex items-center gap-1 hover:text-foreground', numeric(c) && 'flex-row-reverse')}
                                onClick={() => {
                                  setCursors([null]);
                                  setSort(sorted && !sorted.descending ? `-${c.key}` : c.key!);
                                }}
                              >
                                {serverText(c.label)}
                                {sorted ? (sorted.descending ? <ArrowDown className="size-3.5" aria-hidden /> : <ArrowUp className="size-3.5" aria-hidden />) : <ArrowUpDown className="size-3.5 opacity-40" aria-hidden />}
                              </button>
                            ) : (
                              serverText(c.label)
                            )}
                          </TableHead>
                        );
                      })}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((row, i) => (
                      <TableRow key={i}>
                        {columns.map((c) => (
                          <TableCell key={c.key} className={cn(numeric(c) && 'text-right tabular')}>
                            <Cell column={c} value={row[c.key!]} />
                          </TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                  {totals ? (
                    <TableFooter>
                      <TableRow>
                        {columns.map((c, i) => (
                          <TableCell key={c.key} className={cn(numeric(c) && 'text-right tabular')}>
                            {c.key! in totals ? <Cell column={c} value={totals[c.key!]} /> : i === 0 ? t('rep.totals') : null}
                          </TableCell>
                        ))}
                      </TableRow>
                    </TableFooter>
                  ) : null}
                </Table>
              </div>
            )}
            <div className="flex items-center justify-end gap-2 border-t px-3 py-2 text-sm">
              <span className="text-muted-foreground">{t('common.pageOf', { page: cursors.length })}</span>
              <Button variant="outline" size="sm" onClick={() => setCursors((c) => c.slice(0, -1))} disabled={cursors.length <= 1}>
                <ChevronLeft aria-hidden />
                {t('common.previous')}
              </Button>
              <Button variant="outline" size="sm" onClick={() => result?.page?.nextCursor && setCursors((c) => [...c, result.page!.nextCursor!])} disabled={!result?.page?.hasMore}>
                {t('common.next')}
                <ChevronRight aria-hidden />
              </Button>
            </div>
          </Section>
        </>
      )}
      <FormDialog
        open={exporting}
        onOpenChange={setExporting}
        title={t('rep.export')}
        schema={z.object({ format: zf.id() })}
        defaults={{ format: report.exportFormats?.[0] ?? 'CSV' }}
        onSubmit={(v) =>
          request('POST', report.exportPath!, { body: { format: v.format!, parameters }, idempotencyKey: newIdempotencyKey() })
        }
        onDone={() => notify.success(t('rep.exportQueued'))}
      >
        <ExportFormat formats={report.exportFormats ?? []} />
      </FormDialog>
      <SaveReportDialog open={saving} onOpenChange={setSaving} reportCode={report.code!} parameters={parameters} />
    </div>
  );
}

function ExportFormat({ formats }: { formats: string[] }) {
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">{t('rep.exportFormat')}</legend>
      <FormatRadios formats={formats} />
    </fieldset>
  );
}

function FormatRadios({ formats }: { formats: string[] }) {
  const { control } = useFormContext<{ format: string | null }>();
  const { field } = useController({ control, name: 'format' });
  return (
    <RadioGroup value={field.value ?? undefined} onValueChange={field.onChange} className="flex gap-4">
      {formats.map((f) => (
        <div key={f} className="flex items-center gap-2">
          <RadioGroupItem value={f} id={`format-${f}`} />
          <Label htmlFor={`format-${f}`}>{enumLabel(f)}</Label>
        </div>
      ))}
    </RadioGroup>
  );
}

function SaveReportDialog({ open, onOpenChange, reportCode, parameters }: { open: boolean; onOpenChange: (open: boolean) => void; reportCode: string; parameters: Record<string, string> }) {
  const { api, can } = useCompany();
  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={t('rep.saveTitle')}
      schema={z.object({ name: zf.text(100), isShared: z.boolean() })}
      defaults={{ name: '', isShared: false }}
      success={t('common.saved')}
      onSubmit={(v) => api.post('/saved-reports', null, { body: { reportCode, name: v.name, parameters, isShared: v.isShared } })}
    >
      <TextField name="name" label={t('rep.savedName')} required />
      {can('reporting.saved_report.share') ? <CheckboxField name="isShared" label={t('rep.shared')} /> : null}
    </FormDialog>
  );
}
