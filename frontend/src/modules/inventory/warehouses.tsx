import { getRouteApi, Link } from '@tanstack/react-router';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { BooleanBadge } from '@/components/common/status-badge';
import { Code, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities, locationSource } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions } from '@/components/document/actions';
import { ErrorState, LoadingState } from '@/components/feedback/states';
import { EntityField, FieldGrid, SelectField, TextField } from '@/components/form/fields';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { activationActions, MasterDataPage } from '@/components/master/master-data-page';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { StockLevelsTable } from './stock';

type Warehouse = Schemas['Warehouse'];
type Location = Schemas['Location'];
const activeBadge = (active: boolean | undefined) => <BooleanBadge value={active !== false} yes={t('common.active')} no={t('common.inactive')} />;

const warehouseSchema = z.object({
  code: zf.text(20),
  name: zf.text(100),
  branchId: zf.id(),
  addressLine1: zf.optionalText(200),
  addressLine2: zf.optionalText(200),
  city: zf.optionalText(100),
  region: zf.optionalText(100),
  postalCode: zf.optionalText(20),
  countryCode: zf.optionalId(),
});

export function WarehousesPage() {
  const { companyId } = useCompany();
  return (
    <MasterDataPage<Warehouse, z.infer<typeof warehouseSchema>>
      title={t('inv.warehousesTitle')}
      managePermission="inventory.warehouse.manage"
      table={{
        id: 'warehouses',
        fetchPage: (api, query, signal) => api.get('/warehouses', null, { query, signal }),
        rowKey: (w) => w.id!,
        defaultSort: 'code',
        filters: [
          { kind: 'entity', key: 'branchId', label: t('common.branch'), source: entities.branch },
          { kind: 'boolean', key: 'isActive', label: t('common.active') },
        ],
        columns: [
          {
            id: 'code',
            header: t('common.code'),
            sortKey: 'code',
            cell: (w) => (
              <Link to="/c/$companyId/inventory/warehouses/$warehouseId" params={{ companyId, warehouseId: w.id! }} data-row-link className="text-primary hover:underline">
                <Code>{w.code}</Code>
              </Link>
            ),
          },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (w) => w.name },
          { id: 'branch', header: t('common.branch'), hideBelow: 'sm', cell: (w) => <EntityName source={entities.branch} id={w.branchId} /> },
          { id: 'city', header: t('fields.city'), hideBelow: 'md', cell: (w) => <Text value={w.city} /> },
          { id: 'active', header: t('common.status'), cell: (w) => activeBadge(w.isActive) },
        ],
      }}
      form={{
        schema: warehouseSchema,
        values: (w) => ({
          code: w?.code ?? '', name: w?.name ?? '', branchId: w?.branchId ?? null, addressLine1: w?.addressLine1 ?? '', addressLine2: w?.addressLine2 ?? '',
          city: w?.city ?? '', region: w?.region ?? '', postalCode: w?.postalCode ?? '', countryCode: w?.countryCode ?? null,
        }),
        createTitle: t('inv.newWarehouse'),
        editTitle: (w) => t('inv.editWarehouse', { code: w.code }),
        fields: (mode) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
              <EntityField name="branchId" label={t('common.branch')} source={entities.branch} required disabled={mode === 'edit'} />
            </FieldGrid>
            <TextField name="addressLine1" label={t('fields.addressLine1')} />
            <TextField name="addressLine2" label={t('fields.addressLine2')} />
            <FieldGrid>
              <TextField name="city" label={t('fields.city')} />
              <TextField name="region" label={t('fields.region')} />
              <TextField name="postalCode" label={t('fields.postalCode')} />
              <EntityField name="countryCode" label={t('fields.country')} source={entities.country} />
            </FieldGrid>
          </>
        ),
        create: (api, v) => api.post('/warehouses', null, { body: compact(v) as Schemas['WarehouseRequest'] }),
        update: (api, w, v, initial) => {
          const { code: _c, branchId: _b, ...rest } = v;
          const { code: _ic, branchId: _ib, ...before } = initial;
          return api.patch('/warehouses/{warehouseId}', { warehouseId: w.id! }, { body: mergePatch(before, rest), ifMatch: w.version });
        },
      }}
      rowActions={activationActions<Warehouse>(
        (w) => w.isActive !== false,
        (w) => ({
          activate: (api) => api.post('/warehouses/{warehouseId}/activate', { warehouseId: w.id! }, { ifMatch: w.version }),
          deactivate: (api) => api.post('/warehouses/{warehouseId}/deactivate', { warehouseId: w.id! }, { ifMatch: w.version }),
        }),
        'inventory.warehouse.manage',
      )}
    />
  );
}

const route = getRouteApi('/_authed/c/$companyId/inventory/warehouses/$warehouseId');
const locationSchema = z.object({ code: zf.text(40), name: zf.text(100), parentId: zf.optionalId(), locationType: zf.id() });

export function WarehousePage() {
  const { warehouseId } = route.useParams();
  const { api, can } = useCompany();
  const query = useCompanyQuery(['warehouses', warehouseId], (c, signal) => c.get('/warehouses/{warehouseId}', { warehouseId }, { signal }));
  const [editing, setEditing] = useState<{ location?: Location } | null>(null);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const w = query.data;
  const manage = can('inventory.warehouse.manage');
  return (
    <div className="space-y-4">
      <PageHeader title={w.name} description={<Code>{w.code}</Code>} badge={activeBadge(w.isActive)} actions={<AuditHistoryButton entityType="warehouse" entityId={w.id} />} />
      <Section>
        <DetailList
          items={[
            { label: t('common.branch'), value: <EntityName source={entities.branch} id={w.branchId} /> },
            { label: t('fields.city'), value: <Text value={w.city} /> },
            { label: t('fields.country'), value: <Text value={w.countryCode} /> },
          ]}
        />
      </Section>
      <Section title={t('inv.locations')} bodyClassName="p-0" actions={manage ? <Button size="sm" onClick={() => setEditing({})}><Plus aria-hidden />{t('inv.newLocation')}</Button> : null}>
        <DataTable<Location>
          id={`locations-${warehouseId}`}
          fetchPage={(c, q, signal) => c.get('/warehouses/{warehouseId}/locations', { warehouseId }, { query: q, signal })}
          rowKey={(l) => l.id!}
          defaultSort="code"
          filters={[{ kind: 'enum', key: 'locationType', label: t('inv.locationType'), values: [...enums.locationType] }]}
          columns={[
            { id: 'code', header: t('common.code'), sortKey: 'code', cell: (l) => <Code>{l.code}</Code> },
            { id: 'name', header: t('common.name'), sortKey: 'name', cell: (l) => l.name },
            { id: 'type', header: t('inv.locationType'), cell: (l) => enumLabel(l.locationType) },
            { id: 'parent', header: t('fields.parent'), hideBelow: 'md', cell: (l) => <EntityName source={locationSource(warehouseId)} id={l.parentId} /> },
            { id: 'active', header: t('common.status'), cell: (l) => activeBadge(l.isActive) },
            ...(manage
              ? [
                  {
                    id: 'actions',
                    header: <span className="sr-only">{t('common.actions')}</span>,
                    align: 'right' as const,
                    cell: (l: Location) => (
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing({ location: l })}>{t('common.edit')}</Button>
                        <DocumentActions
                          doc={l}
                          actions={activationActions<Location>(
                            (x) => x.isActive !== false,
                            (x) => ({
                              activate: (c) => c.post('/locations/{locationId}/activate', { locationId: x.id! }, { ifMatch: x.version }),
                              deactivate: (c) => c.post('/locations/{locationId}/deactivate', { locationId: x.id! }, { ifMatch: x.version }),
                            }),
                            'inventory.warehouse.manage',
                          )}
                        />
                      </div>
                    ),
                  },
                ]
              : []),
          ]}
        />
      </Section>
      {can('inventory.stock.read') ? (
        <Section title={t('inv.stockTitle')} bodyClassName="p-0">
          <StockLevelsTable warehouseId={warehouseId} />
        </Section>
      ) : null}
      {editing ? (
        <FormDialog
          open
          onOpenChange={(open) => !open && setEditing(null)}
          title={editing.location ? t('inv.editLocation', { code: editing.location.code }) : t('inv.newLocation')}
          schema={locationSchema}
          defaults={{ code: editing.location?.code ?? '', name: editing.location?.name ?? '', parentId: editing.location?.parentId ?? null, locationType: editing.location?.locationType ?? 'INTERNAL' }}
          success={t('common.saved')}
          onSubmit={(v) =>
            editing.location
              ? api.patch('/locations/{locationId}', { locationId: editing.location.id! }, {
                  body: mergePatch({ name: editing.location.name, parentId: editing.location.parentId }, { name: v.name, parentId: v.parentId }),
                  ifMatch: editing.location.version,
                })
              : api.post('/warehouses/{warehouseId}/locations', { warehouseId }, { body: compact(v) as Schemas['LocationRequest'] })
          }
        >
          <FieldGrid>
            <TextField name="code" label={t('common.code')} required disabled={!!editing.location} />
            <TextField name="name" label={t('common.name')} required />
            <SelectField name="locationType" label={t('inv.locationType')} options={enumOptions(enums.locationType)} required disabled={!!editing.location} />
            <EntityField name="parentId" label={t('fields.parent')} source={locationSource(warehouseId)} filter={(l) => l.id !== editing.location?.id} />
          </FieldGrid>
        </FormDialog>
      ) : null}
    </div>
  );
}
