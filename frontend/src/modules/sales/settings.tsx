import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { CheckboxField, DecimalField, FieldGrid, IntegerField, SelectField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { SettingsPage } from '@/components/master/settings-page';
import { t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';

const schema = z.object({
  defaultInvoicePolicy: zf.id(),
  creditCheckMode: zf.id(),
  quotationValidityDays: zf.integer(),
  reserveOnConfirm: z.boolean(),
  discountApprovalThresholdPercent: zf.optionalDecimal(),
});

export function SalesSettingsPage() {
  return (
    <SettingsPage<Schemas['SalesSettings'], z.infer<typeof schema>>
      title={t('sales.settingsTitle')}
      queryKey="sales"
      load={(api, signal) => api.getVersioned('/settings/sales', null, { signal })}
      schema={schema}
      values={(d) => ({
        defaultInvoicePolicy: d.defaultInvoicePolicy ?? 'DELIVERED',
        creditCheckMode: d.creditCheckMode ?? 'NONE',
        quotationValidityDays: d.quotationValidityDays ?? 30,
        reserveOnConfirm: !!d.reserveOnConfirm,
        discountApprovalThresholdPercent: d.discountApprovalThresholdPercent ?? null,
      })}
      fields={() => (
        <>
          <FieldGrid>
            <SelectField name="defaultInvoicePolicy" label={t('sales.defaultInvoicePolicy')} options={enumOptions(enums.invoicePolicy)} required />
            <SelectField name="creditCheckMode" label={t('sales.creditCheckMode')} options={enumOptions(enums.creditCheckMode)} required />
            <IntegerField name="quotationValidityDays" label={t('sales.quotationValidityDays')} min={0} required />
            <DecimalField name="discountApprovalThresholdPercent" label={t('sales.discountThreshold')} suffix="%" />
          </FieldGrid>
          <CheckboxField name="reserveOnConfirm" label={t('sales.reserveOnConfirm')} />
        </>
      )}
      save={(api, v, version) =>
        api.put('/settings/sales', null, {
          body: {
            defaultInvoicePolicy: v.defaultInvoicePolicy!,
            creditCheckMode: v.creditCheckMode!,
            quotationValidityDays: v.quotationValidityDays,
            reserveOnConfirm: v.reserveOnConfirm,
            discountApprovalThresholdPercent: v.discountApprovalThresholdPercent || undefined,
          },
          ifMatch: version,
        })
      }
    />
  );
}
