import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompany } from '@/auth/company';
import { BooleanBadge, StatusBadge } from '@/components/common/status-badge';
import { Code, DateText, Percent, Text } from '@/components/common/values';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import {
  CheckboxField,
  DateField,
  DecimalField,
  EntityField,
  FieldGrid,
  IntegerField,
  SelectField,
  TextField,
} from '@/components/form/fields';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { activationActions, MasterDataPage } from '@/components/master/master-data-page';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';

const activeBadge = (active: boolean | undefined) => (
  <BooleanBadge value={active !== false} yes={t('common.active')} no={t('common.inactive')} />
);

// --- Branches ---------------------------------------------------------------------------------

type Branch = Schemas['BranchResponse'];
const branchSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  addressLine1: zf.optionalText(200),
  addressLine2: zf.optionalText(200),
  city: zf.optionalText(100),
  region: zf.optionalText(100),
  postalCode: zf.optionalText(20),
  countryCode: zf.optionalId(),
});

export function BranchesPage() {
  return (
    <MasterDataPage<Branch, z.infer<typeof branchSchema>>
      title={t('org.branchesTitle')}
      managePermission="org.branch.manage"
      table={{
        id: 'branches',
        fetchPage: (api, query, signal) => api.get('/branches', null, { query, signal }),
        rowKey: (b) => b.id!,
        defaultSort: 'code',
        filters: [{ kind: 'boolean', key: 'isActive', label: t('common.active') }],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (b) => <Code>{b.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (b) => b.name },
          { id: 'city', header: t('fields.city'), hideBelow: 'sm', cell: (b) => <Text value={b.city} /> },
          { id: 'country', header: t('fields.country'), hideBelow: 'md', cell: (b) => <Text value={b.countryCode} /> },
          { id: 'active', header: t('common.status'), cell: (b) => activeBadge(b.isActive) },
        ],
      }}
      form={{
        schema: branchSchema,
        values: (b) => ({
          code: b?.code ?? '',
          name: b?.name ?? '',
          addressLine1: b?.addressLine1 ?? '',
          addressLine2: b?.addressLine2 ?? '',
          city: b?.city ?? '',
          region: b?.region ?? '',
          postalCode: b?.postalCode ?? '',
          countryCode: b?.countryCode ?? null,
        }),
        createTitle: t('org.newBranch'),
        editTitle: (b) => t('org.editBranch', { code: b.code }),
        fields: (mode) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} maxLength={20} />
              <TextField name="name" label={t('common.name')} required />
            </FieldGrid>
            <TextField name="addressLine1" label={t('fields.addressLine1')} />
            <TextField name="addressLine2" label={t('fields.addressLine2')} />
            <FieldGrid>
              <TextField name="city" label={t('fields.city')} />
              <TextField name="region" label={t('fields.region')} />
              <TextField name="postalCode" label={t('fields.postalCode')} />
              <EntityField name="countryCode" label={t('fields.country')} source={entities.country} disabled={mode === 'edit'} />
            </FieldGrid>
          </>
        ),
        create: (api, v) => api.post('/branches', null, { body: compact(v) as Schemas['CreateBranchRequest'] }),
        update: (api, b, v, initial) => {
          const { code: _c, countryCode: _cc, ...patchable } = v;
          const { code: _ic, countryCode: _icc, ...before } = initial;
          return api.patch('/branches/{branchId}', { branchId: b.id! }, { body: mergePatch(before, patchable), ifMatch: b.version });
        },
      }}
      rowActions={activationActions<Branch>(
        (b) => b.isActive !== false,
        (b) => ({
          activate: (api) => api.post('/branches/{branchId}/activate', { branchId: b.id! }, { ifMatch: b.version }),
          deactivate: (api) => api.post('/branches/{branchId}/deactivate', { branchId: b.id! }, { ifMatch: b.version }),
        }),
        'org.branch.manage',
      )}
    />
  );
}

// --- Departments ------------------------------------------------------------------------------

type Department = Schemas['DepartmentResponse'];
const departmentSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  parentId: zf.optionalId(),
  branchId: zf.optionalId(),
});

export function DepartmentsPage() {
  return (
    <MasterDataPage<Department, z.infer<typeof departmentSchema>>
      title={t('org.departmentsTitle')}
      managePermission="org.department.manage"
      table={{
        id: 'departments',
        fetchPage: (api, query, signal) => api.get('/departments', null, { query, signal }),
        rowKey: (d) => d.id!,
        defaultSort: 'code',
        filters: [
          { kind: 'entity', key: 'branchId', label: t('common.branch'), source: entities.branch },
          { kind: 'boolean', key: 'isActive', label: t('common.active') },
        ],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (d) => <Code>{d.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (d) => d.name },
          { id: 'parent', header: t('fields.parent'), hideBelow: 'sm', cell: (d) => <EntityName source={entities.department} id={d.parentId} /> },
          { id: 'branch', header: t('common.branch'), hideBelow: 'md', cell: (d) => <EntityName source={entities.branch} id={d.branchId} /> },
          { id: 'active', header: t('common.status'), cell: (d) => activeBadge(d.isActive) },
        ],
      }}
      form={{
        schema: departmentSchema,
        values: (d) => ({ code: d?.code ?? '', name: d?.name ?? '', parentId: d?.parentId ?? null, branchId: d?.branchId ?? null }),
        createTitle: t('org.newDepartment'),
        editTitle: (d) => t('org.editDepartment', { code: d.code }),
        fields: (mode, d) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
            </FieldGrid>
            <EntityField name="parentId" label={t('fields.parent')} source={entities.department} filter={(x) => x.id !== d?.id} />
            <EntityField name="branchId" label={t('common.branch')} source={entities.branch} />
          </>
        ),
        create: (api, v) => api.post('/departments', null, { body: compact(v) as Schemas['CreateDepartmentRequest'] }),
        update: (api, d, v, initial) => {
          const { code: _c, ...rest } = v;
          const { code: _i, ...before } = initial;
          return api.patch('/departments/{departmentId}', { departmentId: d.id! }, { body: mergePatch(before, rest), ifMatch: d.version });
        },
      }}
      rowActions={activationActions<Department>(
        (d) => d.isActive !== false,
        (d) => ({
          activate: (api) => api.post('/departments/{departmentId}/activate', { departmentId: d.id! }, { ifMatch: d.version }),
          deactivate: (api) => api.post('/departments/{departmentId}/deactivate', { departmentId: d.id! }, { ifMatch: d.version }),
        }),
        'org.department.manage',
      )}
    />
  );
}

// --- Tax codes --------------------------------------------------------------------------------

type TaxCode = Schemas['TaxCodeResponse'];
const taxSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  scope: zf.id(),
  ratePercent: zf.decimal(),
  isExempt: z.boolean(),
  validFrom: zf.optionalDate(),
  validTo: zf.optionalDate(),
});

export function TaxCodesPage() {
  return (
    <MasterDataPage<TaxCode, z.infer<typeof taxSchema>>
      title={t('org.taxCodesTitle')}
      managePermission="org.tax_code.manage"
      table={{
        id: 'tax-codes',
        fetchPage: (api, query, signal) => api.get('/tax-codes', null, { query, signal }),
        rowKey: (x) => x.id!,
        defaultSort: 'code',
        filters: [
          { kind: 'enum', key: 'scope', label: t('fields.scope'), values: [...enums.taxScope] },
          { kind: 'boolean', key: 'isActive', label: t('common.active') },
        ],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (x) => <Code>{x.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (x) => x.name },
          { id: 'scope', header: t('fields.scope'), cell: (x) => enumLabel(x.scope) },
          { id: 'rate', header: t('fields.ratePercent'), align: 'right', cell: (x) => <Percent value={x.ratePercent} /> },
          { id: 'exempt', header: t('fields.exempt'), hideBelow: 'md', cell: (x) => (x.isExempt ? t('common.yes') : '') },
          { id: 'valid', header: t('fields.validFrom'), hideBelow: 'lg', cell: (x) => <DateText value={x.validFrom} /> },
          { id: 'active', header: t('common.status'), cell: (x) => activeBadge(x.isActive) },
        ],
      }}
      form={{
        schema: taxSchema,
        values: (x) => ({
          code: x?.code ?? '',
          name: x?.name ?? '',
          scope: x?.scope ?? 'BOTH',
          ratePercent: x?.ratePercent ?? null,
          isExempt: !!x?.isExempt,
          validFrom: x?.validFrom ?? null,
          validTo: x?.validTo ?? null,
        }),
        createTitle: t('org.newTaxCode'),
        editTitle: (x) => t('org.editTaxCode', { code: x.code }),
        fields: (mode) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
              <SelectField name="scope" label={t('fields.scope')} required options={enumOptions(enums.taxScope)} />
              <DecimalField name="ratePercent" label={t('fields.ratePercent')} required suffix="%" />
              <DateField name="validFrom" label={t('fields.validFrom')} />
              <DateField name="validTo" label={t('fields.validTo')} />
            </FieldGrid>
            <CheckboxField name="isExempt" label={t('fields.exempt')} />
            {mode === 'edit' ? <p className="text-xs text-muted-foreground">{t('org.taxFrozen')}</p> : null}
          </>
        ),
        create: (api, v) => api.post('/tax-codes', null, { body: compact(v) as Schemas['CreateTaxCodeRequest'] }),
        update: (api, x, v, initial) => {
          const { code: _c, ...rest } = v;
          const { code: _i, ...before } = initial;
          return api.patch('/tax-codes/{taxCodeId}', { taxCodeId: x.id! }, { body: mergePatch(before, rest), ifMatch: x.version });
        },
      }}
      rowActions={activationActions<TaxCode>(
        (x) => x.isActive !== false,
        (x) => ({
          activate: (api) => api.post('/tax-codes/{taxCodeId}/activate', { taxCodeId: x.id! }, { ifMatch: x.version }),
          deactivate: (api) => api.post('/tax-codes/{taxCodeId}/deactivate', { taxCodeId: x.id! }, { ifMatch: x.version }),
        }),
        'org.tax_code.manage',
      )}
    />
  );
}

// --- Payment terms ----------------------------------------------------------------------------

type Terms = Schemas['TermsResponse'];
const termsSchema = z.object({ code: zf.text(20), name: zf.text(100), dueDays: zf.integer(), dueBasis: zf.id() });

export function PaymentTermsPage() {
  return (
    <MasterDataPage<Terms, z.infer<typeof termsSchema>>
      title={t('org.paymentTermsTitle')}
      managePermission="org.payment_terms.manage"
      table={{
        id: 'payment-terms',
        fetchPage: (api, query, signal) => api.get('/payment-terms', null, { query, signal }),
        rowKey: (x) => x.id!,
        defaultSort: 'code',
        filters: [{ kind: 'boolean', key: 'isActive', label: t('common.active') }],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (x) => <Code>{x.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (x) => x.name },
          { id: 'days', header: t('fields.dueDays'), sortKey: 'dueDays', align: 'right', cell: (x) => x.dueDays },
          { id: 'basis', header: t('fields.dueBasis'), hideBelow: 'sm', cell: (x) => enumLabel(x.dueBasis) },
          { id: 'active', header: t('common.status'), cell: (x) => activeBadge(x.isActive) },
        ],
      }}
      form={{
        schema: termsSchema,
        values: (x) => ({ code: x?.code ?? '', name: x?.name ?? '', dueDays: x?.dueDays ?? 30, dueBasis: x?.dueBasis ?? 'DOCUMENT_DATE' }),
        createTitle: t('org.newPaymentTerms'),
        editTitle: (x) => t('org.editPaymentTerms', { code: x.code }),
        fields: (mode) => (
          <FieldGrid>
            <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
            <TextField name="name" label={t('common.name')} required />
            <IntegerField name="dueDays" label={t('fields.dueDays')} required min={0} />
            <SelectField name="dueBasis" label={t('fields.dueBasis')} required options={enumOptions(enums.dueBasis)} disabled={mode === 'edit'} />
          </FieldGrid>
        ),
        create: (api, v) => api.post('/payment-terms', null, { body: v as Schemas['CreateTermsRequest'] }),
        update: (api, x, v, initial) =>
          api.patch('/payment-terms/{termsId}', { termsId: x.id! }, {
            body: mergePatch({ name: initial.name, dueDays: initial.dueDays }, { name: v.name, dueDays: v.dueDays }),
            ifMatch: x.version,
          }),
      }}
      rowActions={activationActions<Terms>(
        (x) => x.isActive !== false,
        (x) => ({
          activate: (api) => api.post('/payment-terms/{termsId}/activate', { termsId: x.id! }, { ifMatch: x.version }),
          deactivate: (api) => api.post('/payment-terms/{termsId}/deactivate', { termsId: x.id! }, { ifMatch: x.version }),
        }),
        'org.payment_terms.manage',
      )}
    />
  );
}

// --- Exchange rates ---------------------------------------------------------------------------

type Rate = Schemas['RateResponse'];
const rateSchema = z.object({ currencyCode: zf.id(), rateDate: zf.date(), rate: zf.decimal() });

export function ExchangeRatesPage() {
  const { company } = useCompany();
  return (
    <MasterDataPage<Rate, z.infer<typeof rateSchema>>
      title={t('org.exchangeRatesTitle')}
      description={t('org.exchangeRatesText', { base: company.code ?? '' })}
      managePermission="org.exchange_rate.manage"
      table={{
        id: 'exchange-rates',
        fetchPage: (api, query, signal) => api.get('/exchange-rates', null, { query, signal }),
        rowKey: (x) => x.id!,
        defaultSort: '-rateDate',
        searchable: false,
        filters: [
          { kind: 'entity', key: 'currencyCode', label: t('common.currency'), source: entities.currency },
          { kind: 'dateRange', field: 'rateDate', label: t('fields.rateDate') },
        ],
        columns: [
          { id: 'currency', header: t('common.currency'), sortKey: 'currencyCode', cell: (x) => <Code>{x.currencyCode}</Code> },
          { id: 'date', header: t('fields.rateDate'), sortKey: 'rateDate', cell: (x) => <DateText value={x.rateDate} /> },
          { id: 'rate', header: t('fields.rate'), align: 'right', cell: (x) => <span className="tabular">{x.rate}</span> },
          { id: 'source', header: t('fields.source'), hideBelow: 'sm', cell: (x) => <StatusBadge status={x.source} /> },
        ],
      }}
      form={{
        schema: rateSchema,
        values: (x) => ({ currencyCode: x?.currencyCode ?? null, rateDate: x?.rateDate ?? null, rate: x?.rate ?? null }),
        createTitle: t('org.newExchangeRate'),
        editTitle: (x) => t('org.editExchangeRate', { currency: x.currencyCode, date: x.rateDate }),
        fields: (mode) => (
          <FieldGrid>
            <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} required disabled={mode === 'edit'} />
            <DateField name="rateDate" label={t('fields.rateDate')} required disabled={mode === 'edit'} />
            <DecimalField name="rate" label={t('fields.rate')} required />
          </FieldGrid>
        ),
        create: (api, v) => api.post('/exchange-rates', null, { body: v as Schemas['CreateRateRequest'] }),
        update: (api, x, v) => api.patch('/exchange-rates/{rateId}', { rateId: x.id! }, { body: { rate: v.rate }, ifMatch: x.version }),
      }}
      rowActions={[
        {
          id: 'delete',
          label: t('org.deleteRate'),
          variant: 'destructive',
          permissions: ['org.exchange_rate.manage'],
          confirm: { title: t('org.deleteRateConfirm'), destructive: true },
          run: (api, x) => api.delete('/exchange-rates/{rateId}', { rateId: x.id! }, { ifMatch: x.version }),
          success: t('common.deleted'),
        },
      ]}
    />
  );
}

// --- Partner groups ---------------------------------------------------------------------------

type Group = Schemas['Group'];
const groupSchema = z.object({ code: zf.text(20), name: zf.text(100), appliesTo: zf.id() });

export function PartnerGroupsPage() {
  return (
    <MasterDataPage<Group, z.infer<typeof groupSchema>>
      title={t('org.partnerGroupsTitle')}
      managePermission="partners.partner.manage"
      table={{
        id: 'partner-groups',
        fetchPage: (api, query, signal) => api.get('/partner-groups', null, { query, signal }),
        rowKey: (g) => g.id!,
        defaultSort: 'code',
        filters: [{ kind: 'enum', key: 'appliesTo', label: t('fields.appliesTo'), values: [...enums.groupAppliesTo] }],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (g) => <Code>{g.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (g) => g.name },
          { id: 'applies', header: t('fields.appliesTo'), cell: (g) => enumLabel(g.appliesTo) },
          { id: 'active', header: t('common.status'), cell: (g) => activeBadge(g.isActive) },
        ],
      }}
      form={{
        schema: groupSchema,
        values: (g) => ({ code: g?.code ?? '', name: g?.name ?? '', appliesTo: g?.appliesTo ?? 'CUSTOMER' }),
        createTitle: t('org.newGroup'),
        editTitle: (g) => t('org.editGroup', { code: g.code }),
        fields: (mode) => (
          <FieldGrid>
            <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
            <TextField name="name" label={t('common.name')} required />
            <SelectField name="appliesTo" label={t('fields.appliesTo')} required options={enumOptions(enums.groupAppliesTo)} disabled={mode === 'edit'} />
          </FieldGrid>
        ),
        create: (api, v) => api.post('/partner-groups', null, { body: v as Schemas['GroupRequest'] }),
        update: (api, g, v) => api.patch('/partner-groups/{groupId}', { groupId: g.id! }, { body: mergePatch({ name: g.name }, { name: v.name }), ifMatch: g.version }),
      }}
      rowActions={[
        {
          id: 'deactivate',
          label: t('common.deactivate'),
          when: (g) => g.isActive !== false,
          permissions: ['partners.partner.manage'],
          run: (api, g) => api.patch('/partner-groups/{groupId}', { groupId: g.id! }, { body: { isActive: false }, ifMatch: g.version }),
        },
        {
          id: 'activate',
          label: t('common.activate'),
          when: (g) => g.isActive === false,
          permissions: ['partners.partner.manage'],
          run: (api, g) => api.patch('/partner-groups/{groupId}', { groupId: g.id! }, { body: { isActive: true }, ifMatch: g.version }),
        },
      ]}
    />
  );
}
