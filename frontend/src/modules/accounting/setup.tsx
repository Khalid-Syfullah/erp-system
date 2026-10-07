import { Plus, Save, Trash2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useWatch } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { BooleanBadge, StatusBadge } from '@/components/common/status-badge';
import { Code } from '@/components/common/values';
import { EntityName, EntityPicker } from '@/components/data/entity';
import { entities, type EntitySource } from '@/components/data/entities';
import { DocumentActions, type DocAction } from '@/components/document/actions';
import { EmptyState, ErrorState, LoadingState, ProblemAlert } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { CheckboxField, DecimalField, EntityField, FieldGrid, IntegerField, SelectField, TextareaField, TextField } from '@/components/form/fields';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { MasterDataPage } from '@/components/master/master-data-page';
import { SettingsPage } from '@/components/master/settings-page';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { enumLabel, searchable, t } from '@/i18n';
import { accountSubtypes, enumOptions, enums } from '@/lib/enums';
import { cn } from '@/lib/utils';
import { useFormContext } from 'react-hook-form';

type Account = Schemas['Account'];
type Node = Schemas['AccountNode'];

const accountSchema = z.object({
  code: zf.text(20),
  name: zf.text(150),
  accountType: zf.id(),
  accountSubtype: zf.id(),
  parentId: zf.optionalId(),
  isPostable: z.boolean(),
  currencyCode: zf.optionalId(),
  description: zf.optionalText(500),
});
type AccountValues = z.infer<typeof accountSchema>;

function SubtypeField() {
  const { control } = useFormContext<AccountValues>();
  const type = useWatch({ control, name: 'accountType' });
  return <SelectField name="accountSubtype" label={t('acc.accountSubtype')} options={enumOptions(accountSubtypes[type ?? ''] ?? [])} required />;
}

function flatten(nodes: Node[], depth = 0, out: { account: Account; depth: number; hasChildren: boolean }[] = []) {
  for (const node of nodes) {
    if (!node.account) continue;
    out.push({ account: node.account, depth, hasChildren: (node.children ?? []).length > 0 });
    flatten(node.children ?? [], depth + 1, out);
  }
  return out;
}

/** The chart of accounts as a tree (ADR-038); system accounts cannot be deactivated. */
export function AccountsPage() {
  const { api, can } = useCompany();
  const tree = useCompanyQuery(['accounts', 'tree'], (c, signal) => c.get('/accounts/tree', null, { signal }));
  const [filter, setFilter] = useState('');
  const [editing, setEditing] = useState<{ account?: Account } | null>(null);
  const rows = useMemo(() => {
    const data = tree.data as unknown as Node | Node[] | { data?: Node[] } | undefined;
    const roots = Array.isArray(data) ? data : data && 'data' in data && Array.isArray(data.data) ? data.data : data && 'children' in data ? (data.account ? [data as Node] : (data as Node).children ?? []) : [];
    const all = flatten(roots);
    const term = searchable(filter.trim());
    return term ? all.filter((r) => searchable(`${r.account.code} ${r.account.name}`).includes(term)) : all;
  }, [tree.data, filter]);
  const manage = can('accounting.account.manage');
  const actions: DocAction<Account>[] = [
    { id: 'edit', label: t('common.edit'), permissions: ['accounting.account.manage'], open: (a) => setEditing({ account: a }) },
    {
      id: 'deactivate',
      label: t('common.deactivate'),
      when: (a) => a.status === 'ACTIVE' && !a.isSystem,
      permissions: ['accounting.account.manage'],
      run: (c, a) => c.post('/accounts/{accountId}/deactivate', { accountId: a.id! }, { ifMatch: a.version }),
    },
    {
      id: 'activate',
      label: t('common.activate'),
      when: (a) => a.status === 'INACTIVE',
      permissions: ['accounting.account.manage'],
      run: (c, a) => c.post('/accounts/{accountId}/activate', { accountId: a.id! }, { ifMatch: a.version }),
    },
  ];
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('acc.accountsTitle')}
        actions={manage ? <Button onClick={() => setEditing({})}><Plus aria-hidden />{t('acc.newAccount')}</Button> : null}
      />
      <Section bodyClassName="p-0" title={<Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t('common.searchPlaceholder')} aria-label={t('common.search')} className="w-64" />}>
        {tree.isLoading ? <LoadingState /> : tree.isError ? <ErrorState error={tree.error} onRetry={() => tree.refetch()} /> : rows.length === 0 ? <EmptyState /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('common.code')}</TableHead>
                  <TableHead>{t('common.name')}</TableHead>
                  <TableHead className="hidden md:table-cell">{t('acc.accountType')}</TableHead>
                  <TableHead className="hidden lg:table-cell">{t('acc.accountSubtype')}</TableHead>
                  <TableHead className="hidden sm:table-cell">{t('acc.postable')}</TableHead>
                  <TableHead>{t('common.status')}</TableHead>
                  <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map(({ account: a, depth, hasChildren }) => (
                  <TableRow key={a.id}>
                    <TableCell><Code>{a.code}</Code></TableCell>
                    <TableCell>
                      <span className={cn(hasChildren && 'font-semibold')} style={{ paddingLeft: `${depth * 1.25}rem` }}>
                        {a.name}
                      </span>
                      {a.isControl ? <StatusBadge status="OPEN" tone="info" label={t('acc.control')} className="ml-2" /> : null}
                      {a.isSystem ? <StatusBadge status="SYSTEM" tone="neutral" label={t('acc.system')} className="ml-2" /> : null}
                    </TableCell>
                    <TableCell className="hidden md:table-cell">{enumLabel(a.accountType)}</TableCell>
                    <TableCell className="hidden lg:table-cell">{enumLabel(a.accountSubtype)}</TableCell>
                    <TableCell className="hidden sm:table-cell">{a.isPostable ? t('common.yes') : t('common.no')}</TableCell>
                    <TableCell><StatusBadge status={a.status} /></TableCell>
                    <TableCell className="text-right"><DocumentActions doc={a} actions={actions} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
      {editing ? (
        <FormDialog<AccountValues>
          open
          onOpenChange={(open) => !open && setEditing(null)}
          title={editing.account ? t('acc.editAccount', { code: editing.account.code }) : t('acc.newAccount')}
          size="lg"
          schema={accountSchema}
          defaults={{
            code: editing.account?.code ?? '', name: editing.account?.name ?? '', accountType: editing.account?.accountType ?? 'EXPENSE',
            accountSubtype: editing.account?.accountSubtype ?? 'OPERATING_EXPENSE', parentId: editing.account?.parentId ?? null,
            isPostable: editing.account?.isPostable ?? true, currencyCode: editing.account?.currencyCode ?? null, description: editing.account?.description ?? '',
          }}
          success={t('common.saved')}
          onSubmit={(v) => {
            const a = editing.account;
            if (a) {
              const before = { name: a.name, accountSubtype: a.accountSubtype, parentId: a.parentId, isPostable: a.isPostable, currencyCode: a.currencyCode, description: a.description };
              const { code: _c, accountType: _t, ...rest } = v;
              return api.patch('/accounts/{accountId}', { accountId: a.id! }, { body: mergePatch(before, rest), ifMatch: a.version });
            }
            return api.post('/accounts', null, { body: compact(v) as Schemas['AccountRequest'] });
          }}
        >
          <FieldGrid>
            <TextField name="code" label={t('common.code')} required disabled={!!editing.account} />
            <TextField name="name" label={t('common.name')} required />
            <SelectField name="accountType" label={t('acc.accountType')} options={enumOptions(enums.accountType)} required disabled={!!editing.account} />
            <SubtypeField />
            <EntityField name="parentId" label={t('acc.parentAccount')} source={entities.account as EntitySource<Account>} filter={(x) => x.id !== editing.account?.id} />
            <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} />
          </FieldGrid>
          <CheckboxField name="isPostable" label={t('acc.postable')} />
          <TextareaField name="description" label={t('common.description')} rows={2} />
        </FormDialog>
      ) : null}
    </div>
  );
}

// --- Account mappings -------------------------------------------------------------------------

type MappingRow = { key: string; mappingKey: string; scopeType: string; scopeId: string | null; accountId: string | null };

const scopeSources: Record<string, EntitySource<unknown> | undefined> = {
  PRODUCT_CATEGORY: entities.category as EntitySource<unknown>,
  WAREHOUSE: entities.warehouse as EntitySource<unknown>,
  PARTNER_GROUP: entities.partnerGroup as EntitySource<unknown>,
  TAX_CODE: entities.taxCode as EntitySource<unknown>,
  PAY_COMPONENT: entities.payComponent as EntitySource<unknown>,
  DEPARTMENT: entities.department as EntitySource<unknown>,
  REASON_CODE: entities.reasonCode as EntitySource<unknown>,
};

/** Account determination (bulk replace with If-Match, ACCOUNT_MAPPING_MISSING otherwise at posting). */
export function AccountMappingsPage() {
  const { api } = useCompany();
  const query = useCompanyQuery(['account-mappings'], (c, signal) => c.getVersioned('/account-mappings', null, { signal }));
  const [rows, setRows] = useState<MappingRow[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const loaded = useMemo<MappingRow[]>(
    () =>
      (query.data?.data.mappings ?? []).map((m) => ({
        key: m.id!,
        mappingKey: m.mappingKey!,
        scopeType: m.scopeType!,
        scopeId: m.scopeId ?? null,
        accountId: m.accountId ?? null,
      })),
    [query.data],
  );
  const current = rows ?? loaded;
  const update = (index: number, patch: Partial<MappingRow>) => setRows(current.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await api.put('/account-mappings', null, {
        body: { mappings: current.map((r) => ({ mappingKey: r.mappingKey, scopeType: r.scopeType, scopeId: r.scopeType === 'DEFAULT' ? undefined : (r.scopeId ?? undefined), accountId: r.accountId! })) },
        ifMatch: query.data?.version,
      });
      setRows(null);
      await query.refetch();
      notify.success(t('common.saved'));
    } catch (e) {
      setError(e);
    } finally {
      setSaving(false);
    }
  };
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('acc.mappingsTitle')}
        description={t('acc.mappingsText')}
        actions={
          <>
            <Button variant="outline" onClick={() => setRows([...current, { key: crypto.randomUUID(), mappingKey: 'AR_CONTROL', scopeType: 'DEFAULT', scopeId: null, accountId: null }])}>
              <Plus aria-hidden />
              {t('acc.addMapping')}
            </Button>
            <Button onClick={() => void save()} disabled={rows === null || saving}>
              <Save aria-hidden />
              {t('acc.saveMappings')}
            </Button>
          </>
        }
      />
      <ProblemAlert error={error} />
      <Section bodyClassName="p-0">
        {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('acc.mappingKey')}</TableHead>
                  <TableHead>{t('acc.scopeType')}</TableHead>
                  <TableHead>{t('acc.scopeId')}</TableHead>
                  <TableHead>{t('acc.account')}</TableHead>
                  <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {current.map((row, index) => (
                  <TableRow key={row.key}>
                    <TableCell className="min-w-56">
                      <Select value={row.mappingKey} onValueChange={(v) => update(index, { mappingKey: v })}>
                        <SelectTrigger className="w-full" aria-label={`${t('acc.mappingKey')} ${index + 1}`}><SelectValue /></SelectTrigger>
                        <SelectContent>{enums.mappingKey.map((k) => <SelectItem key={k} value={k}>{enumLabel(k)}</SelectItem>)}</SelectContent>
                      </Select>
                    </TableCell>
                    <TableCell className="min-w-44">
                      <Select value={row.scopeType} onValueChange={(v) => update(index, { scopeType: v, scopeId: null })}>
                        <SelectTrigger className="w-full" aria-label={`${t('acc.scopeType')} ${index + 1}`}><SelectValue /></SelectTrigger>
                        <SelectContent>{enums.mappingScope.map((k) => <SelectItem key={k} value={k}>{enumLabel(k)}</SelectItem>)}</SelectContent>
                      </Select>
                    </TableCell>
                    <TableCell className="min-w-52">
                      {scopeSources[row.scopeType] ? (
                        <EntityPicker source={scopeSources[row.scopeType]!} value={row.scopeId} onChange={(v) => update(index, { scopeId: v })} aria-label={`${t('acc.scopeId')} ${index + 1}`} />
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </TableCell>
                    <TableCell className="min-w-64">
                      <EntityPicker source={entities.account} value={row.accountId} onChange={(v) => update(index, { accountId: v })} aria-label={`${t('acc.account')} ${index + 1}`} clearable={false} />
                    </TableCell>
                    <TableCell>
                      <Button size="icon-sm" variant="ghost" onClick={() => setRows(current.filter((_, i) => i !== index))} aria-label={`${t('common.remove')} ${index + 1}`}>
                        <Trash2 aria-hidden />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
    </div>
  );
}

// --- Journals ---------------------------------------------------------------------------------

type Journal = Schemas['Journal'];
const journalSchema = z.object({ code: zf.text(10), name: zf.text(100), journalType: zf.id() });

export function JournalsPage() {
  return (
    <MasterDataPage<Journal, z.infer<typeof journalSchema>>
      title={t('acc.journalsTitle')}
      managePermission="accounting.journal.manage"
      table={{
        id: 'journals',
        fetchPage: (api, query, signal) => api.get('/journals', null, { query, signal }),
        rowKey: (j) => j.id!,
        defaultSort: 'code',
        searchable: false,
        filters: [{ kind: 'enum', key: 'journalType', label: t('acc.journalType'), values: [...enums.journalType] }],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (j) => <Code>{j.code}</Code> },
          { id: 'name', header: t('common.name'), cell: (j) => j.name },
          { id: 'type', header: t('acc.journalType'), cell: (j) => enumLabel(j.journalType) },
          { id: 'system', header: t('acc.system'), hideBelow: 'sm', cell: (j) => (j.isSystem ? t('common.yes') : '') },
          { id: 'active', header: t('common.status'), cell: (j) => <BooleanBadge value={j.isActive !== false} yes={t('common.active')} no={t('common.inactive')} /> },
        ],
      }}
      form={{
        schema: journalSchema,
        values: (j) => ({ code: j?.code ?? '', name: j?.name ?? '', journalType: j?.journalType ?? 'GENERAL' }),
        createTitle: t('acc.newJournal'),
        editTitle: (j) => t('acc.editJournal', { code: j.code }),
        fields: (mode) => (
          <FieldGrid>
            <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
            <TextField name="name" label={t('common.name')} required />
            <SelectField name="journalType" label={t('acc.journalType')} options={enumOptions(enums.journalType)} required disabled={mode === 'edit'} />
          </FieldGrid>
        ),
        create: (api, v) => api.post('/journals', null, { body: v as Schemas['JournalRequest'] }),
        update: (api, j, v) => api.patch('/journals/{journalId}', { journalId: j.id! }, { body: mergePatch({ name: j.name }, { name: v.name }), ifMatch: j.version }),
      }}
      rowActions={[
        {
          id: 'deactivate',
          label: t('common.deactivate'),
          when: (j) => j.isActive !== false && !j.isSystem,
          permissions: ['accounting.journal.manage'],
          run: (api, j) => api.patch('/journals/{journalId}', { journalId: j.id! }, { body: { isActive: false }, ifMatch: j.version }),
        },
        {
          id: 'activate',
          label: t('common.activate'),
          when: (j) => j.isActive === false,
          permissions: ['accounting.journal.manage'],
          run: (api, j) => api.patch('/journals/{journalId}', { journalId: j.id! }, { body: { isActive: true }, ifMatch: j.version }),
        },
      ]}
    />
  );
}

// --- Settings ---------------------------------------------------------------------------------

const settingsSchema = z.object({
  retainedEarningsAccountId: zf.id(),
  allowManualEntriesInSoftClosed: z.boolean(),
  maxRoundingDifferenceMinorUnits: zf.integer(),
  manualEntryApprovalThresholdBase: zf.optionalDecimal(),
});

export function AccountingSettingsPage() {
  return (
    <SettingsPage<Schemas['AccountingSettings'], z.infer<typeof settingsSchema>>
      title={t('acc.settingsTitle')}
      queryKey="accounting"
      load={(api, signal) => api.getVersioned('/settings/accounting', null, { signal })}
      schema={settingsSchema}
      values={(d) => ({
        retainedEarningsAccountId: d.retainedEarningsAccountId ?? null,
        allowManualEntriesInSoftClosed: !!d.allowManualEntriesInSoftClosed,
        maxRoundingDifferenceMinorUnits: d.maxRoundingDifferenceMinorUnits ?? 0,
        manualEntryApprovalThresholdBase: d.manualEntryApprovalThresholdBase ?? null,
      })}
      fields={(d) => (
        <>
          <DetailList columns={2} items={[{ label: t('acc.coaTemplate'), value: d.coaTemplate }, { label: t('acc.retainedEarnings'), value: <EntityName source={entities.account} id={d.retainedEarningsAccountId} /> }]} />
          <EntityField name="retainedEarningsAccountId" label={t('acc.retainedEarnings')} source={entities.account} filter={(a) => a.accountType === 'EQUITY'} required />
          <IntegerField name="maxRoundingDifferenceMinorUnits" label={t('acc.maxRounding')} min={0} required />
          <DecimalField name="manualEntryApprovalThresholdBase" label={t('acc.manualThreshold')} hint={t('acc.manualThresholdHint')} />
          <CheckboxField name="allowManualEntriesInSoftClosed" label={t('acc.allowSoftClosed')} />
        </>
      )}
      save={(api, v, version) =>
        api.put('/settings/accounting', null, {
          body: {
            retainedEarningsAccountId: v.retainedEarningsAccountId!,
            allowManualEntriesInSoftClosed: v.allowManualEntriesInSoftClosed,
            maxRoundingDifferenceMinorUnits: v.maxRoundingDifferenceMinorUnits,
            manualEntryApprovalThresholdBase: v.manualEntryApprovalThresholdBase || undefined,
          },
          ifMatch: version,
        })
      }
    />
  );
}
