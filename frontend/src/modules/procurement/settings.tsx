import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { DetailList } from '@/components/common/page';
import { DecimalField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { SettingsPage } from '@/components/master/settings-page';
import { t } from '@/i18n';

const schema = z.object({ poApprovalThresholdBase: zf.optionalDecimal(), priceMatchTolerancePercent: zf.decimal(), qtyMatchTolerancePercent: zf.decimal() });

export function ProcurementSettingsPage() {
  return (
    <SettingsPage<Schemas['ProcurementSettings'], z.infer<typeof schema>>
      title={t('proc.settingsTitle')}
      queryKey="procurement"
      load={(api, signal) => api.getVersioned('/settings/procurement', null, { signal })}
      schema={schema}
      values={(d) => ({
        poApprovalThresholdBase: d.poApprovalThresholdBase ?? null,
        priceMatchTolerancePercent: d.priceMatchTolerancePercent ?? '0',
        qtyMatchTolerancePercent: d.qtyMatchTolerancePercent ?? '0',
      })}
      fields={(d) => (
        <>
          <DetailList columns={2} items={[{ label: t('proc.requireReceipt'), value: d.requireReceiptBeforeBill ? t('common.yes') : t('common.no') }]} />
          <DecimalField name="poApprovalThresholdBase" label={t('proc.poApprovalThreshold')} hint={t('proc.poApprovalThresholdHint')} />
          <DecimalField name="priceMatchTolerancePercent" label={t('proc.priceTolerance')} suffix="%" required />
          <DecimalField name="qtyMatchTolerancePercent" label={t('proc.qtyTolerance')} suffix="%" required />
        </>
      )}
      save={(api, v, version) =>
        api.put('/settings/procurement', null, {
          body: {
            poApprovalThresholdBase: v.poApprovalThresholdBase || undefined,
            priceMatchTolerancePercent: v.priceMatchTolerancePercent!,
            qtyMatchTolerancePercent: v.qtyMatchTolerancePercent!,
          },
          ifMatch: version,
        })
      }
    />
  );
}
