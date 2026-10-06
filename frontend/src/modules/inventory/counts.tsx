import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import Decimal from 'decimal.js';
import { Plus, Save } from 'lucide-react';
import { useMemo, useState } from 'react';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { DateText, Quantity } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName, EntityPicker } from '@/components/data/entity';
import { entities, locationById, locationSource } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, inStatus, type DocAction } from '@/components/document/actions';
import { BackLink } from '@/components/document/document-layout';
import { EmptyState, ErrorState, LoadingState, ProblemAlert } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import { DateField, EntityField, TextareaField } from '@/components/form/fields';
import { DecimalInput } from '@/components/form/inputs';
import { zf } from '@/components/form/schema';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { t } from '@/i18n';
import { enums } from '@/lib/enums';
import { isDecimalString, todayIso } from '@/lib/format';
import { cn } from '@/lib/utils';
import { MovementLink } from './movements';

type Count = Schemas['Count'];
type CountLine = Schemas['CountLine'];

const newCountSchema = z.object({ warehouseId: zf.id(), countDate: zf.date(), reasonCodeId: zf.optionalId(), notes: zf.optionalText(1000) });

export function CountsPage() {
  const { api, can, companyId } = useCompany();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  return (
    <div className="space-y-4">
      <PageHeader title={t('inv.countsTitle')} />
      <DataTable<Count>
        id="counts"
        fetchPage={(c, query, signal) => c.get('/stock-counts', null, { query, signal })}
        rowKey={(c) => c.id!}
        searchable={false}
        defaultSort="-createdAt"
        filters={[
          { kind: 'entity', key: 'warehouseId', label: t('common.warehouse'), source: entities.warehouse },
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.countStatus] },
        ]}
        columns={[
          {
            id: 'number',
            header: t('common.number'),
            cell: (c) => (
              <Link to="/c/$companyId/inventory/counts/$countId" params={{ companyId, countId: c.id! }} data-row-link className="font-medium text-primary hover:underline">
                {c.number ?? t('enums.DRAFT')}
              </Link>
            ),
          },
          { id: 'date', header: t('inv.countDate'), sortKey: 'countDate', cell: (c) => <DateText value={c.countDate} /> },
          { id: 'warehouse', header: t('common.warehouse'), cell: (c) => <EntityName source={entities.warehouse} id={c.warehouseId} /> },
          { id: 'lines', header: t('common.lines'), align: 'right', hideBelow: 'sm', cell: (c) => c.lines?.length ?? 0 },
          { id: 'status', header: t('common.status'), cell: (c) => <StatusBadge status={c.status} /> },
        ]}
        toolbar={can('inventory.count.manage') ? <Button onClick={() => setCreating(true)}><Plus aria-hidden />{t('inv.newCount')}</Button> : null}
      />
      <FormDialog
        open={creating}
        onOpenChange={setCreating}
        title={t('inv.newCount')}
        schema={newCountSchema}
        defaults={{ warehouseId: null, countDate: todayIso(), reasonCodeId: null, notes: '' }}
        onSubmit={(v) =>
          api.post('/stock-counts', null, { body: { warehouseId: v.warehouseId!, countDate: v.countDate!, reasonCodeId: v.reasonCodeId ?? undefined, notes: v.notes || undefined } })
        }
        onDone={(created) => void navigate({ to: '/c/$companyId/inventory/counts/$countId', params: { companyId, countId: (created as Count).id! } })}
      >
        <EntityField name="warehouseId" label={t('common.warehouse')} source={entities.warehouse} required />
        <DateField name="countDate" label={t('inv.countDate')} required />
        <EntityField name="reasonCodeId" label={t('inv.reasonCode')} source={entities.reasonCode} filter={(r) => r.appliesTo === 'COUNT'} />
        <TextareaField name="notes" label={t('common.notes')} rows={2} />
      </FormDialog>
    </div>
  );
}

const route = getRouteApi('/_authed/c/$companyId/inventory/counts/$countId');

/**
 * Counting on the warehouse floor (tablets): large touch targets, one card per item and location,
 * the counted quantity entered in the base unit and saved in batches.
 */
export function CountPage() {
  const { countId } = route.useParams();
  const { api, companyId } = useCompany();
  const query = useCompanyQuery(['counts', countId], (c, signal) => c.get('/stock-counts/{countId}', { countId }, { signal }));
  const [entries, setEntries] = useState<Record<string, string | null>>({});
  const [extra, setExtra] = useState<{ variantId: string | null; locationId: string | null }>({ variantId: null, locationId: null });
  const [saveError, setSaveError] = useState<unknown>(null);
  const [saving, setSaving] = useState(false);
  const [posting, setPosting] = useState(false);
  const count = query.data;
  const lineKey = (l: Pick<CountLine, 'variantId' | 'locationId'>) => `${l.variantId}/${l.locationId}`;
  const dirty = Object.keys(entries).length > 0;
  const lines = useMemo(() => count?.lines ?? [], [count]);

  if (query.isLoading) return <LoadingState />;
  if (query.isError || !count) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const editable = count.status === 'IN_PROGRESS';

  const save = async (added?: { variantId: string; locationId: string; counted: string | null }) => {
    setSaving(true);
    setSaveError(null);
    try {
      const body = Object.entries(entries)
        .filter(([, v]) => v === null || isDecimalString(v))
        .map(([key, value]) => {
          const [variantId, locationId] = key.split('/');
          return { variantId: variantId!, locationId: locationId!, countedQuantityBase: value ?? undefined };
        });
      if (added) body.push({ variantId: added.variantId, locationId: added.locationId, countedQuantityBase: added.counted ?? undefined });
      await api.put('/stock-counts/{countId}/lines', { countId }, { body: { lines: body }, ifMatch: count.version });
      setEntries({});
      await query.refetch();
      notify.success(t('common.saved'));
    } catch (error) {
      setSaveError(error);
    } finally {
      setSaving(false);
    }
  };

  const actions: DocAction<Count>[] = [
    { id: 'start', label: t('inv.startCount'), primary: true, when: inStatus('DRAFT'), permissions: ['inventory.count.manage'], run: (c, x) => c.post('/stock-counts/{countId}/start', { countId: x.id! }, { ifMatch: x.version }) },
    {
      id: 'complete',
      label: t('inv.completeCount'),
      primary: true,
      when: (x) => x.status === 'IN_PROGRESS' && !dirty,
      permissions: ['inventory.count.manage'],
      run: (c, x) => c.post('/stock-counts/{countId}/complete', { countId: x.id! }, { ifMatch: x.version }),
    },
    { id: 'post', label: t('inv.postCount'), primary: true, when: inStatus('COMPLETED'), permissions: ['inventory.count.post'], open: () => setPosting(true) },
    {
      id: 'cancel',
      label: t('inv.cancelCount'),
      variant: 'destructive',
      when: inStatus('DRAFT', 'IN_PROGRESS', 'COMPLETED'),
      permissions: ['inventory.count.manage'],
      confirm: { title: t('inv.cancelCount'), destructive: true },
      run: (c, x) => c.post('/stock-counts/{countId}/cancel', { countId: x.id! }, { ifMatch: x.version }),
    },
  ];

  return (
    <div className="space-y-4">
      <PageHeader
        title={count.number ?? t('inv.newCount')}
        badge={<StatusBadge status={count.status} />}
        breadcrumbs={<BackLink to={`/c/${companyId}/inventory/counts`} label={t('inv.countsTitle')} />}
        actions={
          <>
            <AuditHistoryButton entityType="stock_count" entityId={count.id} />
            {editable ? (
              <Button size="lg" onClick={() => void save()} disabled={!dirty || saving}>
                <Save aria-hidden />
                {t('inv.saveCounts')}
              </Button>
            ) : null}
            <DocumentActions doc={count} actions={actions} />
          </>
        }
      />
      <Section>
        <DetailList
          items={[
            { label: t('common.warehouse'), value: <EntityName source={entities.warehouse} id={count.warehouseId} /> },
            { label: t('inv.countDate'), value: <DateText value={count.countDate} /> },
            { label: t('inv.reasonCode'), value: <EntityName source={entities.reasonCode} id={count.reasonCodeId} /> },
            count.adjustmentMovementId ? { label: t('inv.adjustmentMovement'), value: <MovementLink id={count.adjustmentMovementId} number={t('common.view')} /> } : null,
          ]}
        />
        {editable ? <p className="mt-3 text-sm text-muted-foreground">{t('inv.countHint')}</p> : null}
      </Section>
      {saveError ? <ProblemAlert error={saveError} /> : null}
      {lines.length === 0 ? (
        <EmptyState />
      ) : (
        <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3" aria-label={t('common.lines')}>
          {lines.map((line) => {
            const key = lineKey(line);
            const value = key in entries ? entries[key] : (line.countedQuantityBase ?? null);
            const difference = isDecimalString(value) && isDecimalString(line.systemQuantityBase) ? new Decimal(value).minus(line.systemQuantityBase).toFixed() : null;
            const inputId = `count-${line.id}`;
            return (
              <li key={line.id} className={cn('space-y-3 rounded-lg border bg-card p-4', key in entries && 'border-primary')}>
                <div>
                  <div className="font-medium"><EntityName source={entities.variant} id={line.variantId} /></div>
                  <div className="text-sm text-muted-foreground"><EntityName source={locationById} id={line.locationId} /></div>
                </div>
                <div className="grid grid-cols-3 items-end gap-2 text-sm">
                  <div>
                    <div className="text-xs text-muted-foreground">{t('inv.systemQty')}</div>
                    <Quantity value={line.systemQuantityBase} className="text-base" />
                  </div>
                  <div>
                    <Label htmlFor={inputId} className="text-xs text-muted-foreground">{t('inv.countedQty')}</Label>
                    {editable ? (
                      <DecimalInput id={inputId} value={value} onChange={(v) => setEntries((e) => ({ ...e, [key]: v }))} className="[&_input]:h-11 [&_input]:text-lg" />
                    ) : (
                      <Quantity value={line.countedQuantityBase} className="text-base" />
                    )}
                  </div>
                  <div className="text-right">
                    <div className="text-xs text-muted-foreground">{t('inv.difference')}</div>
                    <Quantity value={difference} className={cn('text-base', difference && difference !== '0' && 'font-semibold text-status-warning-fg')} />
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      {editable ? (
        <Section title={t('inv.addCountLine')}>
          <div className="flex flex-wrap items-end gap-3">
            <div className="w-full space-y-1.5 sm:w-72">
              <Label id="extra-variant">{t('inv.variant')}</Label>
              <EntityPicker source={entities.variant} value={extra.variantId} onChange={(v) => setExtra((x) => ({ ...x, variantId: v }))} aria-labelledby="extra-variant" size="lg" />
            </div>
            <div className="w-full space-y-1.5 sm:w-64">
              <Label id="extra-location">{t('inv.location')}</Label>
              <EntityPicker source={locationSource(count.warehouseId)} value={extra.locationId} onChange={(v) => setExtra((x) => ({ ...x, locationId: v }))} aria-labelledby="extra-location" size="lg" />
            </div>
            <Button
              size="lg"
              variant="outline"
              disabled={!extra.variantId || !extra.locationId || saving}
              onClick={() => void save({ variantId: extra.variantId!, locationId: extra.locationId!, counted: null }).then(() => setExtra({ variantId: null, locationId: null }))}
            >
              <Plus aria-hidden />
              {t('common.add')}
            </Button>
          </div>
        </Section>
      ) : null}
      <FormDialog
        open={posting}
        onOpenChange={setPosting}
        title={t('inv.postCount')}
        description={t('inv.postCountConfirm')}
        schema={z.object({ reasonCodeId: zf.optionalId() })}
        defaults={{ reasonCodeId: count.reasonCodeId ?? null }}
        submitLabel={t('inv.postCount')}
        success={t('enums.POSTED')}
        onSubmit={(v, key) =>
          api.post('/stock-counts/{countId}/post', { countId }, { body: { reasonCodeId: v.reasonCodeId ?? undefined }, ifMatch: count.version, idempotencyKey: key })
        }
      >
        <EntityField name="reasonCodeId" label={t('inv.reasonCode')} source={entities.reasonCode} filter={(r) => r.appliesTo === 'COUNT'} />
      </FormDialog>
    </div>
  );
}
