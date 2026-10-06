import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { companyKey, useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { meQuery } from '@/auth/session';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { FieldGrid, Form, IntegerField, SelectField, TextField } from '@/components/form/fields';
import { mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { SettingsPage } from '@/components/master/settings-page';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Input } from '@/components/ui/input';
import { t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { enumLabel } from '@/i18n';
import { useFormContext, useWatch } from 'react-hook-form';

type Company = Schemas['CompanyCompanyResponse'];

const schema = z.object({
  legalName: zf.text(200),
  displayName: zf.text(100),
  taxRegistrationNo: zf.optionalText(50),
  registrationNo: zf.optionalText(50),
  timezone: zf.text(64),
  fiscalYearStartMonth: zf.integer(),
  addressLine1: zf.optionalText(200),
  addressLine2: zf.optionalText(200),
  city: zf.optionalText(100),
  region: zf.optionalText(100),
  postalCode: zf.optionalText(20),
  roundingMode: zf.id(),
  taxRounding: zf.id(),
});

function valuesOf(c: Company): z.infer<typeof schema> {
  return {
    legalName: c.legalName ?? '',
    displayName: c.displayName ?? '',
    taxRegistrationNo: c.taxRegistrationNo ?? '',
    registrationNo: c.registrationNo ?? '',
    timezone: c.timezone ?? 'UTC',
    fiscalYearStartMonth: c.fiscalYearStartMonth ?? 1,
    addressLine1: c.addressLine1 ?? '',
    addressLine2: c.addressLine2 ?? '',
    city: c.city ?? '',
    region: c.region ?? '',
    postalCode: c.postalCode ?? '',
    roundingMode: c.roundingMode ?? 'HALF_UP',
    taxRounding: c.taxRounding ?? 'PER_LINE',
  };
}

/** The company profile; the base currency and country are fixed once the company exists. */
export function CompanyPage() {
  const query = useCompanyQuery(['company'], (api, signal) => api.get('', null, { signal }));
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  return <CompanyForm company={query.data} />;
}

function CompanyForm({ company }: { company: Company }) {
  const { api, companyId, can } = useCompany();
  const queryClient = useQueryClient();
  const initial = valuesOf(company);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), values: initial });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await api.patch('', null, { body: mergePatch(initial, values), ifMatch: company.version });
    await queryClient.invalidateQueries({ queryKey: companyKey(companyId) });
    await queryClient.invalidateQueries({ queryKey: meQuery.queryKey });
    notify.success(t('common.saved'));
  });
  const editable = can('org.company.manage');
  return (
    <div className="max-w-4xl space-y-4">
      <PageHeader title={t('org.companyTitle')} description={company.code} badge={<StatusBadge status={company.status} />} />
      <Section>
        <DetailList
          items={[
            { label: t('fields.baseCurrency'), value: company.baseCurrency },
            { label: t('fields.country'), value: company.countryCode },
          ]}
        />
      </Section>
      <Section>
        <Form form={form} onSubmit={submit}>
          <fieldset disabled={!editable} className="space-y-4">
            <FieldGrid>
              <TextField name="displayName" label={t('fields.displayName')} required />
              <TextField name="legalName" label={t('fields.legalName')} required />
              <TextField name="taxRegistrationNo" label={t('fields.taxRegistrationNo')} />
              <TextField name="registrationNo" label={t('fields.registrationNo')} />
              <TextField name="timezone" label={t('fields.timezone')} required />
              <IntegerField name="fiscalYearStartMonth" label={t('fields.fiscalYearStartMonth')} min={1} max={12} required />
            </FieldGrid>
            <FieldGrid>
              <TextField name="addressLine1" label={t('fields.addressLine1')} />
              <TextField name="addressLine2" label={t('fields.addressLine2')} />
              <TextField name="city" label={t('fields.city')} />
              <TextField name="region" label={t('fields.region')} />
              <TextField name="postalCode" label={t('fields.postalCode')} />
            </FieldGrid>
            <h2 className="text-sm font-semibold">{t('org.companySettings')}</h2>
            <FieldGrid>
              <SelectField name="roundingMode" label={t('fields.roundingMode')} options={enumOptions(enums.roundingMode)} required />
              <SelectField name="taxRounding" label={t('fields.taxRounding')} options={enumOptions(enums.taxRounding)} required />
            </FieldGrid>
          </fieldset>
          <FormProblem {...problem} />
          {editable ? (
            <Button type="submit" disabled={form.formState.isSubmitting || !form.formState.isDirty}>
              {t('common.save')}
            </Button>
          ) : null}
        </Form>
      </Section>
    </div>
  );
}

// --- Numbering --------------------------------------------------------------------------------

type Formats = Schemas['SettingsResponse'];
const numberingSchema = z.object({
  formats: z.array(z.object({ documentType: z.string(), prefix: z.string().max(20, t('forms.tooLong', { max: 20 })), padding: zf.integer(), isDefault: z.boolean() })),
});
type NumberingValues = z.infer<typeof numberingSchema>;

/** Prefix and digits per document type (G-6). Unchanged default formats are left out of the PUT. */
export function NumberingPage() {
  return (
    <SettingsPage<Formats, NumberingValues>
      title={t('org.numberingTitle')}
      description={t('org.numberingText')}
      queryKey="numbering"
      load={(api, signal) => api.getVersioned('/settings/numbering', null, { signal })}
      schema={numberingSchema}
      values={(d) => ({
        formats: (d.formats ?? []).map((f) => ({ documentType: f.documentType!, prefix: f.prefix ?? '', padding: f.padding ?? 6, isDefault: !!f.isDefault })),
      })}
      fields={(d) => <NumberingTable formats={d.formats ?? []} />}
      save={(api, values, version, data) => {
        const before = new Map((data.formats ?? []).map((f) => [f.documentType, f]));
        const formats: Record<string, { prefix: string; padding: number }> = {};
        for (const f of values.formats) {
          const old = before.get(f.documentType);
          const changed = !old || old.prefix !== f.prefix || old.padding !== f.padding;
          if (!f.isDefault || changed) formats[f.documentType] = { prefix: f.prefix, padding: f.padding };
        }
        return api.put('/settings/numbering', null, { body: { formats }, ifMatch: version });
      }}
    />
  );
}

function NumberingTable({ formats }: { formats: Schemas['FormatResponse'][] }) {
  const { register, control } = useFormContext<NumberingValues>();
  const values = useWatch({ control, name: 'formats' });
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t('fields.documentType')}</TableHead>
            <TableHead>{t('fields.prefix')}</TableHead>
            <TableHead>{t('fields.padding')}</TableHead>
            <TableHead>{t('fields.example')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {formats.map((f, i) => (
            <TableRow key={f.documentType}>
              <TableCell>{enumLabel(f.documentType)}</TableCell>
              <TableCell>
                <Input {...register(`formats.${i}.prefix`)} aria-label={`${t('fields.prefix')} ${enumLabel(f.documentType)}`} className="w-40" />
              </TableCell>
              <TableCell>
                <Input
                  type="number"
                  min={1}
                  max={12}
                  {...register(`formats.${i}.padding`, { valueAsNumber: true })}
                  aria-label={`${t('fields.padding')} ${enumLabel(f.documentType)}`}
                  className="w-20"
                />
              </TableCell>
              <TableCell className="font-mono text-xs text-muted-foreground">
                {values?.[i] ? `${values[i]!.prefix}${'1'.padStart(Number(values[i]!.padding) || 1, '0')}` : f.example}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
