import { Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useFieldArray, useFormContext } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { BooleanBadge } from '@/components/common/status-badge';
import { Code, DateText, Percent, Quantity, Text } from '@/components/common/values';
import { entities } from '@/components/data/entities';
import { LineDecimal, LineEntity } from '@/components/document/lines-editor';
import { CheckboxField, DateField, DecimalField, EntityField, FieldGrid, IntegerField, SelectField, TextField } from '@/components/form/fields';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { MasterDataPage } from '@/components/master/master-data-page';
import { SettingsPage } from '@/components/master/settings-page';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';

const activeBadge = (active: boolean | undefined) => <BooleanBadge value={active !== false} yes={t('common.active')} no={t('common.inactive')} />;

// --- Pay components ---------------------------------------------------------------------------

type Component = Schemas['Component'];
const componentSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  kind: zf.id(),
  calculation: zf.id(),
  defaultRate: zf.optionalDecimal(),
  defaultAmount: zf.optionalDecimal(),
  isTaxable: z.boolean(),
  statutoryRuleCode: zf.optionalId(),
  sequence: zf.integer(),
});

function StatutoryRuleField() {
  const rules = useCompanyQuery(['statutory-rules'], (c, signal) => c.get('/statutory-rules', null, { signal }));
  return (
    <SelectField
      name="statutoryRuleCode"
      label={t('pay.statutoryRule')}
      allowEmpty
      options={(rules.data?.data ?? []).map((r) => ({ value: r.code!, label: `${r.code} — ${r.description}` }))}
    />
  );
}

export function PayComponentsPage() {
  return (
    <MasterDataPage<Component, z.infer<typeof componentSchema>>
      title={t('pay.componentsTitle')}
      managePermission="payroll.configuration.manage"
      table={{
        id: 'pay-components',
        fetchPage: (api, query, signal) => api.get('/pay-components', null, { query, signal }),
        rowKey: (c) => c.id!,
        defaultSort: 'sequence',
        filters: [{ kind: 'enum', key: 'kind', label: t('pay.kind'), values: [...enums.componentKind] }],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (c) => <Code>{c.code}</Code> },
          { id: 'name', header: t('common.name'), cell: (c) => c.name },
          { id: 'kind', header: t('pay.kind'), cell: (c) => enumLabel(c.kind) },
          { id: 'calc', header: t('pay.calculation'), hideBelow: 'sm', cell: (c) => enumLabel(c.calculation) },
          { id: 'rate', header: t('pay.defaultRate'), align: 'right', hideBelow: 'md', cell: (c) => <Percent value={c.defaultRate} /> },
          { id: 'amount', header: t('pay.defaultAmount'), align: 'right', hideBelow: 'md', cell: (c) => <Quantity value={c.defaultAmount} /> },
          { id: 'seq', header: t('pay.sequence'), sortKey: 'sequence', align: 'right', hideBelow: 'lg', cell: (c) => c.sequence },
          { id: 'active', header: t('common.status'), cell: (c) => activeBadge(c.active) },
        ],
      }}
      form={{
        schema: componentSchema,
        values: (c) => ({
          code: c?.code ?? '', name: c?.name ?? '', kind: c?.kind ?? 'EARNING', calculation: c?.calculation ?? 'FIXED',
          defaultRate: c?.defaultRate ?? null, defaultAmount: c?.defaultAmount ?? null, isTaxable: c?.taxable ?? true,
          statutoryRuleCode: c?.statutoryRuleCode ?? null, sequence: c?.sequence ?? 10,
        }),
        createTitle: t('pay.newComponent'),
        editTitle: (c) => t('pay.editComponent', { code: c.code }),
        fields: (mode) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
              <SelectField name="kind" label={t('pay.kind')} options={enumOptions(enums.componentKind)} required disabled={mode === 'edit'} />
              <SelectField name="calculation" label={t('pay.calculation')} options={enumOptions(enums.componentCalculation)} required disabled={mode === 'edit'} />
              <DecimalField name="defaultRate" label={t('pay.defaultRate')} suffix="%" />
              <DecimalField name="defaultAmount" label={t('pay.defaultAmount')} />
              <StatutoryRuleField />
              <IntegerField name="sequence" label={t('pay.sequence')} required />
            </FieldGrid>
            <CheckboxField name="isTaxable" label={t('pay.taxable')} />
          </>
        ),
        create: (api, v) => api.post('/pay-components', null, { body: compact(v) as Schemas['ComponentRequest'] }),
        update: (api, c, v, initial) => {
          const { code: _c, kind: _k, calculation: _ca, ...rest } = v;
          const { code: _ic, kind: _ik, calculation: _ica, ...before } = initial;
          return api.patch('/pay-components/{componentId}', { componentId: c.id! }, { body: mergePatch(before, rest), ifMatch: c.version });
        },
      }}
      rowActions={[
        { id: 'deactivate', label: t('common.deactivate'), when: (c) => c.active !== false, permissions: ['payroll.configuration.manage'], run: (api, c) => api.patch('/pay-components/{componentId}', { componentId: c.id! }, { body: { isActive: false }, ifMatch: c.version }) },
        { id: 'activate', label: t('common.activate'), when: (c) => c.active === false, permissions: ['payroll.configuration.manage'], run: (api, c) => api.patch('/pay-components/{componentId}', { componentId: c.id! }, { body: { isActive: true }, ifMatch: c.version }) },
      ]}
    />
  );
}

// --- Salary structures ------------------------------------------------------------------------

type Structure = Schemas['Structure'];
const structureSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  isActive: z.boolean(),
  components: z.array(z.object({ componentId: zf.id(), rate: zf.optionalDecimal(), amount: zf.optionalDecimal() })),
});
type StructureValues = z.infer<typeof structureSchema>;

function StructureComponents() {
  const { control } = useFormContext<StructureValues>();
  const { fields, append, remove } = useFieldArray({ control, name: 'components' });
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">{t('pay.components')}</legend>
      {fields.map((field, i) => (
        <div key={field.id} className="grid grid-cols-[1fr_7rem_7rem_auto] items-start gap-2">
          <LineEntity name={`components.${i}.componentId`} label={`${t('pay.components')} ${i + 1}`} source={entities.payComponent} />
          <LineDecimal name={`components.${i}.rate`} label={`${t('pay.rate')} ${i + 1}`} suffix="%" />
          <LineDecimal name={`components.${i}.amount`} label={`${t('pay.amount')} ${i + 1}`} />
          <Button type="button" size="icon-sm" variant="ghost" onClick={() => remove(i)} aria-label={`${t('common.remove')} ${i + 1}`}><Trash2 aria-hidden /></Button>
        </div>
      ))}
      <Button type="button" size="sm" variant="outline" onClick={() => append({ componentId: null, rate: null, amount: null })}>
        <Plus aria-hidden />
        {t('pay.addComponent')}
      </Button>
    </fieldset>
  );
}

function structureBody(v: StructureValues) {
  return { code: v.code, name: v.name, isActive: v.isActive, components: v.components.map((c) => ({ componentId: c.componentId!, rate: c.rate || undefined, amount: c.amount || undefined })) };
}

export function SalaryStructuresPage() {
  return (
    <MasterDataPage<Structure, StructureValues>
      title={t('pay.structuresTitle')}
      managePermission="payroll.configuration.manage"
      table={{
        id: 'salary-structures',
        fetchPage: (api, query, signal) => api.get('/salary-structures', null, { query, signal }),
        rowKey: (s) => s.id!,
        defaultSort: 'code',
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (s) => <Code>{s.code}</Code> },
          { id: 'name', header: t('common.name'), cell: (s) => s.name },
          { id: 'components', header: t('pay.components'), cell: (s) => (s.components ?? []).map((c) => c.componentCode).join(', ') },
          { id: 'active', header: t('common.status'), cell: (s) => activeBadge(s.active) },
        ],
      }}
      form={{
        schema: structureSchema,
        wide: true,
        values: (s) => ({
          code: s?.code ?? '', name: s?.name ?? '', isActive: s?.active ?? true,
          components: (s?.components ?? []).map((c) => ({ componentId: c.componentId ?? null, rate: c.rate ?? null, amount: c.amount ?? null })),
        }),
        createTitle: t('pay.newStructure'),
        editTitle: (s) => t('pay.editStructure', { code: s.code }),
        fields: (mode) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
            </FieldGrid>
            {mode === 'edit' ? <CheckboxField name="isActive" label={t('common.active')} /> : null}
            <StructureComponents />
          </>
        ),
        create: (api, v) => api.post('/salary-structures', null, { body: structureBody(v) }),
        update: (api, s, v) => api.put('/salary-structures/{structureId}', { structureId: s.id! }, { body: structureBody(v), ifMatch: s.version }),
      }}
    />
  );
}

// --- Pay schedules ----------------------------------------------------------------------------

type Schedule = Schemas['Schedule'];
const scheduleSchema = z.object({ code: zf.text(20), name: zf.text(100), frequency: zf.id(), currencyCode: zf.id(), anchorDate: zf.optionalDate(), payDayOffset: zf.optionalInteger() });

export function PaySchedulesPage() {
  const { api } = useCompany();
  const [generating, setGenerating] = useState<Schedule | null>(null);
  return (
    <>
      <MasterDataPage<Schedule, z.infer<typeof scheduleSchema>>
        title={t('pay.schedulesTitle')}
        managePermission="payroll.configuration.manage"
        table={{
          id: 'pay-schedules',
          fetchPage: (c, query, signal) => c.get('/pay-schedules', null, { query, signal }),
          rowKey: (s) => s.id!,
          defaultSort: 'code',
          searchable: false,
          columns: [
            { id: 'code', header: t('common.code'), sortKey: 'code', cell: (s) => <Code>{s.code}</Code> },
            { id: 'name', header: t('common.name'), cell: (s) => s.name },
            { id: 'frequency', header: t('pay.frequency'), cell: (s) => enumLabel(s.frequency) },
            { id: 'currency', header: t('common.currency'), cell: (s) => s.currencyCode },
            { id: 'anchor', header: t('pay.anchorDate'), hideBelow: 'md', cell: (s) => <DateText value={s.anchorDate} /> },
            { id: 'offset', header: t('pay.payDayOffset'), align: 'right', hideBelow: 'md', cell: (s) => <Text value={s.payDayOffset} /> },
            { id: 'active', header: t('common.status'), cell: (s) => activeBadge(s.active) },
          ],
        }}
        form={{
          schema: scheduleSchema,
          values: (s) => ({ code: s?.code ?? '', name: s?.name ?? '', frequency: s?.frequency ?? 'MONTHLY', currencyCode: s?.currencyCode ?? null, anchorDate: s?.anchorDate ?? null, payDayOffset: s?.payDayOffset ?? null }),
          createTitle: t('pay.newSchedule'),
          editTitle: (s) => t('pay.editSchedule', { code: s.code }),
          fields: (mode) => (
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
              <SelectField name="frequency" label={t('pay.frequency')} options={enumOptions(enums.payFrequency)} required disabled={mode === 'edit'} />
              <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} required disabled={mode === 'edit'} />
              <DateField name="anchorDate" label={t('pay.anchorDate')} disabled={mode === 'edit'} />
              <IntegerField name="payDayOffset" label={t('pay.payDayOffset')} />
            </FieldGrid>
          ),
          create: (c, v) => c.post('/pay-schedules', null, { body: compact(v) as Schemas['ScheduleRequest'] }),
          update: (c, s, v) => c.patch('/pay-schedules/{scheduleId}', { scheduleId: s.id! }, { body: mergePatch({ name: s.name, payDayOffset: s.payDayOffset }, { name: v.name, payDayOffset: v.payDayOffset }), ifMatch: s.version }),
        }}
        rowActions={[{ id: 'periods', label: t('pay.generatePeriods'), permissions: ['payroll.configuration.manage'], open: (s) => setGenerating(s) }]}
      />
      <FormDialog
        open={generating !== null}
        onOpenChange={(open) => !open && setGenerating(null)}
        title={t('pay.generatePeriods')}
        description={generating?.name}
        schema={z.object({ year: zf.integer() })}
        defaults={{ year: new Date().getFullYear() }}
        success={t('common.saved')}
        onSubmit={(v) => api.post('/pay-schedules/{scheduleId}/periods', { scheduleId: generating!.id! }, { body: { year: v.year } })}
      >
        <IntegerField name="year" label={t('pay.year')} required />
      </FormDialog>
    </>
  );
}

// --- Settings ---------------------------------------------------------------------------------

export function PayrollSettingsPage() {
  return (
    <SettingsPage<Schemas['PayrollSettings'], { prorationBasis: string | null }>
      title={t('pay.settingsTitle')}
      queryKey="payroll"
      load={async (api, signal) => {
        const data = await api.get('/settings/payroll', null, { signal });
        return { data, version: data.version ?? null };
      }}
      schema={z.object({ prorationBasis: zf.id() })}
      values={(d) => ({ prorationBasis: d.prorationBasis ?? 'CALENDAR_DAYS' })}
      fields={() => <SelectField name="prorationBasis" label={t('pay.prorationBasis')} options={enumOptions(enums.prorationBasis)} required />}
      save={(api, v, version) => api.put('/settings/payroll', null, { body: { prorationBasis: v.prorationBasis! }, ifMatch: version })}
    />
  );
}

