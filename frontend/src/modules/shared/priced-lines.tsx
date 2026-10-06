// Priced document lines (purchase orders, bills, quotations, sales orders, invoices): the editor and
// the read-only table. The server prices, discounts and taxes every line (G-7); the editor only
// collects inputs, pre-filling the unit and tax code from the product as a convenience.
import { useFormContext, type ArrayPath, type FieldArray, type FieldValues } from 'react-hook-form';
import { z } from 'zod';
import type { CompanyApi, Schemas } from '@/api/client';
import { useCompany } from '@/auth/company';
import { Money, Percent, Quantity, Text } from '@/components/common/values';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { type LineColumnDef } from '@/components/document/document-layout';
import { LineDecimal, LineEntity, LinesEditor, LineText, type LineColumn } from '@/components/document/lines-editor';
import { zf } from '@/components/form/schema';
import { t } from '@/i18n';

export const pricedLineSchema = z.object({
  variantId: zf.id(),
  description: zf.optionalText(500),
  quantity: zf.decimal(),
  uomId: zf.id(),
  unitPrice: zf.optionalDecimal(),
  discountPercent: zf.optionalDecimal(),
  taxCodeId: zf.optionalId(),
});
export type PricedLineValues = z.infer<typeof pricedLineSchema>;

export const emptyPricedLine = (): PricedLineValues => ({
  variantId: null,
  description: '',
  quantity: '1',
  uomId: null,
  unitPrice: null,
  discountPercent: null,
  taxCodeId: null,
});

interface PricedSource {
  variantId?: string;
  description?: string;
  quantity?: string;
  uomId?: string;
  unitPrice?: string;
  discountPercent?: string;
  taxCodeId?: string;
}

export function pricedLineValues(lines: PricedSource[] | undefined): PricedLineValues[] {
  if (!lines || lines.length === 0) return [emptyPricedLine()];
  return lines.map((l) => ({
    variantId: l.variantId ?? null,
    description: l.description ?? '',
    quantity: l.quantity ?? null,
    uomId: l.uomId ?? null,
    unitPrice: l.unitPrice ?? null,
    discountPercent: l.discountPercent && l.discountPercent !== '0' ? l.discountPercent : null,
    taxCodeId: l.taxCodeId ?? null,
  }));
}

/** The request lines: empty optional values are left out (a missing price takes the price list's). */
export function pricedLinesBody(lines: PricedLineValues[]) {
  return lines.map((l) => ({
    variantId: l.variantId!,
    description: l.description || undefined,
    quantity: l.quantity!,
    uomId: l.uomId!,
    unitPrice: l.unitPrice || undefined,
    discountPercent: l.discountPercent || undefined,
    taxCodeId: l.taxCodeId ?? undefined,
  }));
}

async function productDefaults(api: CompanyApi, variant: Schemas['Variant'] | undefined, side: 'purchase' | 'sales') {
  if (!variant?.productId) return null;
  const product = await api.get('/products/{productId}', { productId: variant.productId });
  return side === 'purchase'
    ? { uomId: product.purchaseUomId ?? product.baseUomId, taxCodeId: product.purchaseTaxCodeId, description: product.name }
    : { uomId: product.salesUomId ?? product.baseUomId, taxCodeId: product.salesTaxCodeId, description: product.name };
}

export function PricedLinesEditor<T extends FieldValues>({
  name,
  side,
  priceHint,
}: {
  name: ArrayPath<T>;
  side: 'purchase' | 'sales';
  priceHint?: string;
}) {
  const { api } = useCompany();
  const form = useFormContext();
  const set = (path: string, value: unknown) => form.setValue(path, value, { shouldDirty: true });
  const columns: LineColumn[] = [
    {
      key: 'variant',
      header: t('doc.item'),
      className: 'min-w-56',
      render: (i) => (
        <LineEntity
          name={`${name}.${i}.variantId`}
          label={`${t('doc.item')} ${i + 1}`}
          source={entities.variant}
          onSelect={(variant) =>
            void productDefaults(api, variant, side).then((d) => {
              if (!d) return;
              if (d.uomId) set(`${name}.${i}.uomId`, d.uomId);
              if (d.taxCodeId && !form.getValues(`${name}.${i}.taxCodeId`)) set(`${name}.${i}.taxCodeId`, d.taxCodeId);
            })
          }
        />
      ),
    },
    { key: 'description', header: t('doc.description'), className: 'min-w-40', render: (i) => <LineText name={`${name}.${i}.description`} label={`${t('doc.description')} ${i + 1}`} /> },
    { key: 'qty', header: t('common.quantity'), align: 'right', render: (i) => <LineDecimal name={`${name}.${i}.quantity`} label={`${t('common.quantity')} ${i + 1}`} /> },
    { key: 'uom', header: t('inv.uom'), className: 'min-w-28', render: (i) => <LineEntity name={`${name}.${i}.uomId`} label={`${t('inv.uom')} ${i + 1}`} source={entities.uom} /> },
    { key: 'price', header: priceHint ? `${t('doc.unitPrice')} *` : t('doc.unitPrice'), align: 'right', render: (i) => <LineDecimal name={`${name}.${i}.unitPrice`} label={`${t('doc.unitPrice')} ${i + 1}`} /> },
    { key: 'discount', header: t('doc.discount'), align: 'right', render: (i) => <LineDecimal name={`${name}.${i}.discountPercent`} label={`${t('doc.discount')} ${i + 1}`} /> },
    {
      key: 'tax',
      header: t('doc.tax'),
      className: 'min-w-36',
      render: (i) => (
        <LineEntity
          name={`${name}.${i}.taxCodeId`}
          label={`${t('doc.tax')} ${i + 1}`}
          source={entities.taxCode}
          filter={(x) => x.scope === 'BOTH' || x.scope === (side === 'purchase' ? 'PURCHASE' : 'SALES')}
        />
      ),
    },
  ];
  return (
    <div className="space-y-2">
      <LinesEditor<T> name={name} columns={columns} newLine={emptyPricedLine as () => FieldArray<T, ArrayPath<T>>} caption={t('common.lines')} />
      {priceHint ? <p className="text-xs text-muted-foreground">* {priceHint}</p> : null}
    </div>
  );
}

interface PricedLine {
  id?: string;
  lineNo?: number;
  variantId?: string;
  description?: string;
  quantity?: string;
  uomId?: string;
  unitPrice?: string;
  discountPercent?: string;
  taxCodeId?: string;
  netAmount?: string;
  taxAmount?: string;
  totalAmount?: string;
}

/** Read-only columns of priced lines, with extra progress columns per document. */
export function pricedLineColumns<L extends PricedLine>(currency: string | null | undefined, extra: LineColumnDef<L>[] = []): LineColumnDef<L>[] {
  return [
    { id: 'no', header: '#', cell: (l) => l.lineNo },
    {
      id: 'item',
      header: t('doc.item'),
      cell: (l) => (
        <div>
          <EntityName source={entities.variant} id={l.variantId} />
          {l.description ? <div className="text-xs text-muted-foreground">{l.description}</div> : null}
        </div>
      ),
    },
    { id: 'qty', header: t('common.quantity'), align: 'right', cell: (l) => <Quantity value={l.quantity} /> },
    { id: 'uom', header: t('inv.uom'), cell: (l) => <EntityName source={entities.uom} id={l.uomId} /> },
    { id: 'price', header: t('doc.unitPrice'), align: 'right', cell: (l) => <Money value={l.unitPrice} currency={currency} showCurrency={false} /> },
    { id: 'discount', header: t('doc.discount'), align: 'right', hideBelow: 'lg', cell: (l) => (l.discountPercent && l.discountPercent !== '0' ? <Percent value={l.discountPercent} /> : <Text value={null} />) },
    { id: 'tax', header: t('doc.tax'), hideBelow: 'md', cell: (l) => <EntityName source={entities.taxCode} id={l.taxCodeId} /> },
    ...extra,
    { id: 'net', header: t('doc.net'), align: 'right', hideBelow: 'sm', cell: (l) => <Money value={l.netAmount} currency={currency} showCurrency={false} /> },
    { id: 'total', header: t('doc.lineTotal'), align: 'right', cell: (l) => <Money value={l.totalAmount} currency={currency} showCurrency={false} /> },
  ];
}
