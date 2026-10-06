import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { DetailList } from '@/components/common/page';
import { DecimalField } from '@/components/form/fields';
import { zf } from '@/components/form/schema';
import { SettingsPage } from '@/components/master/settings-page';
import { enumLabel, t } from '@/i18n';

const schema = z.object({ overReceiptTolerancePercent: zf.decimal(), adjustmentApprovalThreshold: zf.optionalDecimal() });

export function InventorySettingsPage() {
  return (
    <SettingsPage<Schemas['InventorySettings'], z.infer<typeof schema>>
      title={t('inv.settingsTitle')}
      queryKey="inventory"
      load={(api, signal) => api.getVersioned('/settings/inventory', null, { signal })}
      schema={schema}
      values={(d) => ({ overReceiptTolerancePercent: d.overReceiptTolerancePercent ?? '0', adjustmentApprovalThreshold: d.adjustmentApprovalThreshold ?? null })}
      fields={(d) => (
        <>
          <DetailList
            columns={2}
            items={[
              { label: t('inv.costingMethod'), value: enumLabel(d.costingMethod) },
              { label: t('inv.allowNegativeStock'), value: d.allowNegativeStock ? t('common.yes') : t('common.no') },
            ]}
          />
          <DecimalField name="overReceiptTolerancePercent" label={t('inv.overReceiptTolerance')} suffix="%" required />
          <DecimalField name="adjustmentApprovalThreshold" label={t('inv.adjustmentApprovalThreshold')} hint={t('inv.adjustmentApprovalHint')} />
        </>
      )}
      save={(api, v, version) =>
        api.put('/settings/inventory', null, {
          body: { overReceiptTolerancePercent: v.overReceiptTolerancePercent!, adjustmentApprovalThreshold: v.adjustmentApprovalThreshold || undefined },
          ifMatch: version,
        })
      }
    />
  );
}
