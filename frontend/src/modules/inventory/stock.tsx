import { useState } from 'react';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { PageHeader, Section } from '@/components/common/page';
import { DateText, DateTimeText, Money, Quantity } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName, EntityPicker } from '@/components/data/entity';
import { entities, locationById } from '@/components/data/entities';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { DateInput } from '@/components/form/inputs';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { enumLabel, t } from '@/i18n';
import { enums } from '@/lib/enums';
import { todayIso } from '@/lib/format';

/** Stock per warehouse (on hand, reserved, available), optionally of one item. */
export function StockLevelsTable({ variantId, warehouseId }: { variantId?: string; warehouseId?: string }) {
  const fixed = { ...(variantId ? { variantId } : {}), ...(warehouseId ? { warehouseId } : {}) };
  return (
    <DataTable<Schemas['StockLevel']>
      id={`stock-levels-${variantId ?? ''}-${warehouseId ?? ''}`}
      fetchPage={(api, query, signal) => api.get('/stock-levels', null, { query, signal })}
      rowKey={(s) => `${s.variantId}-${s.warehouseId}`}
      searchable={false}
      fixedFilters={fixed}
      filters={[
        ...(warehouseId ? [] : [{ kind: 'entity' as const, key: 'warehouseId', label: t('common.warehouse'), source: entities.warehouse }]),
        ...(variantId ? [] : [{ kind: 'entity' as const, key: 'variantId', label: t('inv.variant'), source: entities.variant }]),
      ]}
      columns={[
        { id: 'variant', header: t('inv.variant'), cell: (s) => <EntityName source={entities.variant} id={s.variantId} /> },
        { id: 'warehouse', header: t('common.warehouse'), cell: (s) => <EntityName source={entities.warehouse} id={s.warehouseId} /> },
        { id: 'onHand', header: t('inv.onHand'), align: 'right', cell: (s) => <Quantity value={s.onHand} /> },
        { id: 'reserved', header: t('inv.reserved'), align: 'right', cell: (s) => <Quantity value={s.reserved} /> },
        { id: 'available', header: t('inv.available'), align: 'right', cell: (s) => <Quantity value={s.available} className="font-medium" /> },
      ]}
    />
  );
}

export function StockLevelsPage() {
  const [view, setView] = useState<'warehouse' | 'location'>('warehouse');
  return (
    <div className="space-y-4">
      <PageHeader
        title={t('inv.stockTitle')}
        actions={
          <ToggleGroup type="single" value={view} onValueChange={(v) => v && setView(v as 'warehouse' | 'location')} variant="outline" aria-label={t('inv.stockTitle')}>
            <ToggleGroupItem value="warehouse">{t('inv.byWarehouse')}</ToggleGroupItem>
            <ToggleGroupItem value="location">{t('inv.byLocation')}</ToggleGroupItem>
          </ToggleGroup>
        }
      />
      {view === 'warehouse' ? (
        <StockLevelsTable />
      ) : (
        <DataTable<Schemas['LocationLevel']>
          id="stock-by-location"
          fetchPage={(api, query, signal) => api.get('/stock-levels/by-location', null, { query, signal })}
          rowKey={(s) => `${s.variantId}-${s.locationId}`}
          searchable={false}
          filters={[
            { kind: 'entity', key: 'warehouseId', label: t('common.warehouse'), source: entities.warehouse },
            { kind: 'entity', key: 'variantId', label: t('inv.variant'), source: entities.variant },
          ]}
          columns={[
            { id: 'variant', header: t('inv.variant'), cell: (s) => <EntityName source={entities.variant} id={s.variantId} /> },
            { id: 'warehouse', header: t('common.warehouse'), cell: (s) => <EntityName source={entities.warehouse} id={s.warehouseId} /> },
            { id: 'location', header: t('inv.location'), cell: (s) => <EntityName source={locationById} id={s.locationId} /> },
            { id: 'onHand', header: t('inv.onHand'), align: 'right', cell: (s) => <Quantity value={s.onHand} /> },
          ]}
        />
      )}
    </div>
  );
}

/** The inventory ledger: one immutable transaction per stock change (INV-1). */
export function StockLedgerPage() {
  return (
    <div className="space-y-4">
      <PageHeader title={t('inv.ledgerTitle')} />
      <DataTable<Schemas['InventoryLedgerEntry']>
        id="inventory-ledger"
        fetchPage={(api, query, signal) => api.get('/inventory-transactions', null, { query, signal })}
        rowKey={(e) => e.id!}
        searchable={false}
        defaultSort="-createdAt"
        filters={[
          { kind: 'entity', key: 'warehouseId', label: t('common.warehouse'), source: entities.warehouse },
          { kind: 'entity', key: 'variantId', label: t('inv.variant'), source: entities.variant },
          { kind: 'enum', key: 'movementType', label: t('inv.movementType'), values: [...enums.movementType] },
          { kind: 'dateRange', field: 'transactionDate', label: t('inv.transactionDate') },
        ]}
        columns={[
          { id: 'date', header: t('inv.transactionDate'), sortKey: 'transactionDate', cell: (e) => <DateText value={e.transactionDate} /> },
          { id: 'type', header: t('inv.movementType'), cell: (e) => enumLabel(e.movementType) },
          { id: 'variant', header: t('inv.variant'), cell: (e) => <EntityName source={entities.variant} id={e.variantId} /> },
          { id: 'warehouse', header: t('common.warehouse'), hideBelow: 'md', cell: (e) => <EntityName source={entities.warehouse} id={e.warehouseId} /> },
          { id: 'location', header: t('inv.location'), hideBelow: 'lg', cell: (e) => <EntityName source={locationById} id={e.locationId} /> },
          { id: 'qty', header: t('inv.quantityBase'), align: 'right', cell: (e) => <Quantity value={e.quantityBase} /> },
          { id: 'cost', header: t('inv.unitCost'), align: 'right', hideBelow: 'sm', cell: (e) => <Money value={e.unitCostBase} showCurrency={false} /> },
          { id: 'value', header: t('inv.value'), align: 'right', cell: (e) => <Money value={e.valueBase} showCurrency={false} /> },
          { id: 'created', header: t('common.createdAt'), sortKey: 'createdAt', hideBelow: 'lg', cell: (e) => <DateTimeText value={e.createdAt} /> },
        ]}
      />
    </div>
  );
}

/** Moving-average valuation per item on a date (`inventory.valuation.read`). */
export function ValuationPage() {
  const [asOf, setAsOf] = useState(todayIso());
  const [variantId, setVariantId] = useState<string | null>(null);
  const query = useCompanyQuery(['valuation', asOf, variantId], (api, signal) =>
    api.get('/stock-valuation', null, { query: { asOf, variantId: variantId ?? undefined }, signal }),
  );
  const rows = query.data?.data ?? [];
  return (
    <div className="space-y-4">
      <PageHeader title={t('inv.valuationTitle')} />
      <Section>
        <div className="flex flex-wrap items-end gap-3">
          <div className="space-y-1.5">
            <Label htmlFor="val-as-of">{t('inv.asOf')}</Label>
            <DateInput id="val-as-of" value={asOf} onChange={(v) => setAsOf(v ?? todayIso())} />
          </div>
          <div className="w-full space-y-1.5 sm:w-80">
            <Label id="val-variant">{t('inv.variant')}</Label>
            <EntityPicker source={entities.variant} value={variantId} onChange={setVariantId} aria-labelledby="val-variant" />
          </div>
        </div>
      </Section>
      <Section bodyClassName="p-0">
        {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState /> : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('inv.variant')}</TableHead>
                  <TableHead className="text-right">{t('inv.quantityBase')}</TableHead>
                  <TableHead className="text-right">{t('inv.averageCost')}</TableHead>
                  <TableHead className="text-right">{t('inv.totalValue')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((v) => (
                  <TableRow key={v.variantId}>
                    <TableCell><EntityName source={entities.variant} id={v.variantId} /></TableCell>
                    <TableCell className="text-right"><Quantity value={v.quantityBase} /></TableCell>
                    <TableCell className="text-right"><Money value={v.averageCostBase} showCurrency={false} /></TableCell>
                    <TableCell className="text-right"><Money value={v.totalValueBase} showCurrency={false} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
              <TableFooter>
                <TableRow>
                  <TableCell colSpan={3}>{t('common.total')}</TableCell>
                  <TableCell className="text-right"><Money value={query.data?.totalValueBase} showCurrency={false} /></TableCell>
                </TableRow>
              </TableFooter>
            </Table>
          </div>
        )}
      </Section>
    </div>
  );
}
