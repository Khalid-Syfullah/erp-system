import { getRouteApi, Link } from '@tanstack/react-router';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { BooleanBadge, StatusBadge } from '@/components/common/status-badge';
import { Code, DateText, Money, Quantity } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions } from '@/components/document/actions';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { CheckboxField, DateField, DecimalField, EntityField, FieldGrid, TextField } from '@/components/form/fields';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { MasterDataPage } from '@/components/master/master-data-page';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { t } from '@/i18n';

type PriceList = Schemas['PriceList'];
type Item = Schemas['PriceListItem'];

const listSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  currencyCode: zf.id(),
  pricesIncludeTax: z.boolean(),
  customerGroupId: zf.optionalId(),
  isDefault: z.boolean(),
  validFrom: zf.optionalDate(),
  validTo: zf.optionalDate(),
  isActive: z.boolean(),
});
type ListValues = z.infer<typeof listSchema>;

export function PriceListsPage() {
  const { companyId } = useCompany();
  return (
    <MasterDataPage<PriceList, ListValues>
      title={t('sales.priceListsTitle')}
      managePermission="sales.price_list.manage"
      table={{
        id: 'price-lists',
        fetchPage: (api, query, signal) => api.get('/price-lists', null, { query, signal }),
        rowKey: (p) => p.id!,
        defaultSort: 'code',
        filters: [
          { kind: 'entity', key: 'currencyCode', label: t('common.currency'), source: entities.currency },
          { kind: 'boolean', key: 'isActive', label: t('common.active') },
        ],
        columns: [
          {
            id: 'code',
            header: t('common.code'),
            sortKey: 'code',
            cell: (p) => (
              <Link to="/c/$companyId/sales/price-lists/$listId" params={{ companyId, listId: p.id! }} data-row-link className="text-primary hover:underline">
                <Code>{p.code}</Code>
              </Link>
            ),
          },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (p) => p.name },
          { id: 'currency', header: t('common.currency'), cell: (p) => p.currencyCode },
          { id: 'default', header: t('fields.isDefault'), hideBelow: 'sm', cell: (p) => (p.isDefault ? t('common.yes') : '') },
          { id: 'valid', header: t('fields.validFrom'), hideBelow: 'md', cell: (p) => <DateText value={p.validFrom} /> },
          { id: 'active', header: t('common.status'), cell: (p) => <BooleanBadge value={p.isActive !== false} yes={t('common.active')} no={t('common.inactive')} /> },
        ],
      }}
      form={{
        schema: listSchema,
        values: (p) => ({
          code: p?.code ?? '', name: p?.name ?? '', currencyCode: p?.currencyCode ?? null, pricesIncludeTax: !!p?.pricesIncludeTax,
          customerGroupId: p?.customerGroupId ?? null, isDefault: !!p?.isDefault, validFrom: p?.validFrom ?? null, validTo: p?.validTo ?? null, isActive: p?.isActive ?? true,
        }),
        createTitle: t('sales.newPriceList'),
        editTitle: (p) => t('sales.editPriceList', { code: p.code }),
        fields: (mode) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
              <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} required disabled={mode === 'edit'} />
              <EntityField name="customerGroupId" label={t('fields.group')} source={entities.partnerGroup} filter={(g) => g.appliesTo === 'CUSTOMER'} />
              <DateField name="validFrom" label={t('fields.validFrom')} />
              <DateField name="validTo" label={t('fields.validTo')} />
            </FieldGrid>
            <CheckboxField name="pricesIncludeTax" label={t('doc.pricesIncludeTax')} />
            <CheckboxField name="isDefault" label={t('fields.isDefault')} />
            {mode === 'edit' ? <CheckboxField name="isActive" label={t('common.active')} /> : null}
          </>
        ),
        create: (api, v) => api.post('/price-lists', null, { body: compact(v) as Schemas['PriceListRequest'] }),
        update: (api, p, v, initial) => {
          const { code: _c, currencyCode: _cur, ...rest } = v;
          const { code: _ic, currencyCode: _icur, ...before } = initial;
          return api.patch('/price-lists/{listId}', { listId: p.id! }, { body: mergePatch(before, rest), ifMatch: p.version });
        },
      }}
    />
  );
}

const route = getRouteApi('/_authed/c/$companyId/sales/price-lists/$listId');
const itemSchema = z.object({ variantId: zf.id(), uomId: zf.id(), minQuantity: zf.optionalDecimal(), unitPrice: zf.decimal(), validFrom: zf.optionalDate(), validTo: zf.optionalDate() });

export function PriceListPage() {
  const { listId } = route.useParams();
  const { api, can } = useCompany();
  const query = useCompanyQuery(['price-lists', listId], (c, signal) => c.get('/price-lists/{listId}', { listId }, { signal }));
  const [editing, setEditing] = useState<{ item?: Item } | null>(null);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const p = query.data;
  const manage = can('sales.price_list.manage');
  return (
    <div className="space-y-4">
      <PageHeader
        title={p.name}
        description={<Code>{p.code}</Code>}
        badge={p.isDefault ? <StatusBadge status="ACTIVE" label={t('fields.isDefault')} /> : null}
        actions={<AuditHistoryButton entityType="price_list" entityId={p.id} />}
      />
      <Section>
        <DetailList
          items={[
            { label: t('common.currency'), value: p.currencyCode },
            { label: t('doc.pricesIncludeTax'), value: p.pricesIncludeTax ? t('common.yes') : t('common.no') },
            { label: t('fields.validFrom'), value: <DateText value={p.validFrom} /> },
            { label: t('fields.validTo'), value: <DateText value={p.validTo} /> },
          ]}
        />
      </Section>
      <Section title={t('sales.items')} bodyClassName="p-0" actions={manage ? <Button size="sm" onClick={() => setEditing({})}><Plus aria-hidden />{t('sales.newItem')}</Button> : null}>
        <DataTable<Item>
          id={`price-list-items-${listId}`}
          fetchPage={(c, q, signal) => c.get('/price-lists/{listId}/items', { listId }, { query: q, signal })}
          rowKey={(i) => i.id!}
          searchable={false}
          filters={[{ kind: 'entity', key: 'variantId', label: t('doc.item'), source: entities.variant }]}
          columns={[
            { id: 'item', header: t('doc.item'), cell: (i) => <EntityName source={entities.variant} id={i.variantId} /> },
            { id: 'uom', header: t('inv.uom'), cell: (i) => <EntityName source={entities.uom} id={i.uomId} /> },
            { id: 'min', header: t('sales.minQuantity'), sortKey: 'minQuantity', align: 'right', cell: (i) => <Quantity value={i.minQuantity} /> },
            { id: 'price', header: t('doc.unitPrice'), align: 'right', cell: (i) => <Money value={i.unitPrice} currency={p.currencyCode} /> },
            { id: 'from', header: t('fields.validFrom'), hideBelow: 'md', cell: (i) => <DateText value={i.validFrom} /> },
            { id: 'to', header: t('fields.validTo'), hideBelow: 'md', cell: (i) => <DateText value={i.validTo} /> },
            ...(manage
              ? [
                  {
                    id: 'actions',
                    header: <span className="sr-only">{t('common.actions')}</span>,
                    align: 'right' as const,
                    cell: (i: Item) => (
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing({ item: i })}>{t('common.edit')}</Button>
                        <DocumentActions
                          doc={i}
                          actions={[
                            {
                              id: 'delete',
                              label: t('common.delete'),
                              variant: 'destructive',
                              confirm: { title: t('common.delete'), destructive: true },
                              run: (c, x) => c.delete('/price-lists/{listId}/items/{itemId}', { listId, itemId: x.id! }, { ifMatch: x.version }),
                            },
                          ]}
                        />
                      </div>
                    ),
                  },
                ]
              : []),
          ]}
        />
      </Section>
      {editing ? (
        <FormDialog
          open
          onOpenChange={(open) => !open && setEditing(null)}
          title={editing.item ? t('sales.editItem') : t('sales.newItem')}
          schema={itemSchema}
          defaults={{
            variantId: editing.item?.variantId ?? null, uomId: editing.item?.uomId ?? null, minQuantity: editing.item?.minQuantity ?? null,
            unitPrice: editing.item?.unitPrice ?? null, validFrom: editing.item?.validFrom ?? null, validTo: editing.item?.validTo ?? null,
          }}
          success={t('common.saved')}
          onSubmit={(v) =>
            editing.item
              ? api.patch('/price-lists/{listId}/items/{itemId}', { listId, itemId: editing.item.id! }, {
                  body: mergePatch(
                    { minQuantity: editing.item.minQuantity, unitPrice: editing.item.unitPrice, validFrom: editing.item.validFrom, validTo: editing.item.validTo },
                    { minQuantity: v.minQuantity, unitPrice: v.unitPrice, validFrom: v.validFrom, validTo: v.validTo },
                  ),
                  ifMatch: editing.item.version,
                })
              : api.post('/price-lists/{listId}/items', { listId }, { body: compact(v) as Schemas['ItemRequest'] })
          }
        >
          <EntityField name="variantId" label={t('doc.item')} source={entities.variant} required disabled={!!editing.item} />
          <FieldGrid>
            <EntityField name="uomId" label={t('inv.uom')} source={entities.uom} required disabled={!!editing.item} />
            <DecimalField name="minQuantity" label={t('sales.minQuantity')} />
            <DecimalField name="unitPrice" label={t('doc.unitPrice')} required suffix={p.currencyCode} />
            <DateField name="validFrom" label={t('fields.validFrom')} />
            <DateField name="validTo" label={t('fields.validTo')} />
          </FieldGrid>
        </FormDialog>
      ) : null}
    </div>
  );
}
