import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';
import type { Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { BooleanBadge, StatusBadge } from '@/components/common/status-badge';
import { Code, Quantity, Text } from '@/components/common/values';
import { DataTable } from '@/components/data/data-table';
import { EntityName } from '@/components/data/entity';
import { entities } from '@/components/data/entities';
import { AuditHistoryButton } from '@/components/document/audit-panel';
import { DocumentActions, type DocAction } from '@/components/document/actions';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback/states';
import { notify } from '@/components/feedback/notify';
import {
  CheckboxField,
  DecimalField,
  EntityField,
  FieldGrid,
  Form,
  IntegerField,
  SelectField,
  TextareaField,
  TextField,
} from '@/components/form/fields';
import { compact, mergePatch, zf } from '@/components/form/schema';
import { FormProblem, useSubmit } from '@/components/form/use-submit';
import { activationActions, MasterDataPage } from '@/components/master/master-data-page';
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Drawer } from '@/components/overlay/drawer';
import { FormDialog } from '@/components/overlay/form-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';
import { StockLevelsTable } from './stock';

type Product = Schemas['Product'];
type Variant = Schemas['Variant'];

const activeBadge = (active: boolean | undefined) => <BooleanBadge value={active !== false} yes={t('common.active')} no={t('common.inactive')} />;

export function ProductLink({ id, children }: { id: string | undefined; children: React.ReactNode }) {
  const { companyId } = useCompany();
  if (!id) return <>{children}</>;
  return (
    <Link to="/c/$companyId/inventory/products/$productId" params={{ companyId, productId: id }} data-row-link className="font-medium text-primary hover:underline">
      {children}
    </Link>
  );
}

// --- Products ---------------------------------------------------------------------------------

const productSchema = z.object({
  code: zf.text(40),
  name: zf.text(200),
  description: zf.optionalText(2000),
  categoryId: zf.id(),
  productType: zf.id(),
  baseUomId: zf.id(),
  purchaseUomId: zf.optionalId(),
  salesUomId: zf.optionalId(),
  isPurchasable: z.boolean(),
  isSellable: z.boolean(),
  salesTaxCodeId: zf.optionalId(),
  purchaseTaxCodeId: zf.optionalId(),
  hasVariants: z.boolean(),
  sku: zf.optionalText(40),
  barcode: zf.optionalText(40),
});
type ProductValues = z.infer<typeof productSchema>;

function productValues(p?: Product): ProductValues {
  return {
    code: p?.code ?? '',
    name: p?.name ?? '',
    description: p?.description ?? '',
    categoryId: p?.categoryId ?? null,
    productType: p?.productType ?? 'STOCKABLE',
    baseUomId: p?.baseUomId ?? null,
    purchaseUomId: p?.purchaseUomId ?? null,
    salesUomId: p?.salesUomId ?? null,
    isPurchasable: p?.isPurchasable ?? true,
    isSellable: p?.isSellable ?? true,
    salesTaxCodeId: p?.salesTaxCodeId ?? null,
    purchaseTaxCodeId: p?.purchaseTaxCodeId ?? null,
    hasVariants: p?.hasVariants ?? false,
    sku: '',
    barcode: '',
  };
}

function ProductFields({ mode }: { mode: 'create' | 'edit' }) {
  return (
    <>
      <FieldGrid>
        <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
        <TextField name="name" label={t('common.name')} required />
        <EntityField name="categoryId" label={t('inv.category')} source={entities.category} required />
        <SelectField name="productType" label={t('inv.productType')} options={enumOptions(enums.productType)} required disabled={mode === 'edit'} />
        <EntityField name="baseUomId" label={t('inv.baseUom')} source={entities.uom} required />
        <EntityField name="purchaseUomId" label={t('inv.purchaseUom')} source={entities.uom} />
        <EntityField name="salesUomId" label={t('inv.salesUom')} source={entities.uom} />
        <EntityField name="salesTaxCodeId" label={t('inv.salesTax')} source={entities.taxCode} filter={(x) => x.scope !== 'PURCHASE'} />
        <EntityField name="purchaseTaxCodeId" label={t('inv.purchaseTax')} source={entities.taxCode} filter={(x) => x.scope !== 'SALES'} />
      </FieldGrid>
      <TextareaField name="description" label={t('common.description')} />
      <div className="flex flex-wrap gap-6">
        <CheckboxField name="isPurchasable" label={t('inv.purchasable')} />
        <CheckboxField name="isSellable" label={t('inv.sellable')} />
        {mode === 'create' ? <CheckboxField name="hasVariants" label={t('inv.hasVariants')} /> : null}
      </div>
      {mode === 'create' ? (
        <FieldGrid>
          <TextField name="sku" label={t('inv.sku')} hint={t('common.optional')} />
          <TextField name="barcode" label={t('inv.barcode')} />
        </FieldGrid>
      ) : null}
    </>
  );
}

export function ProductsPage() {
  const { can, api, companyId } = useCompany();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const create = can('inventory.product.manage') ? (
    <Button onClick={() => setCreating(true)}>
      <Plus aria-hidden />
      {t('inv.newProduct')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('inv.productsTitle')} />
      <DataTable<Product>
        id="products"
        fetchPage={(c, query, signal) => c.get('/products', null, { query, signal })}
        rowKey={(p) => p.id!}
        defaultSort="code"
        filters={[
          { kind: 'enum', key: 'productType', label: t('inv.productType'), values: [...enums.productType] },
          { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.productStatus] },
          { kind: 'entity', key: 'categoryId', label: t('inv.category'), source: entities.category },
        ]}
        columns={[
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (p) => <ProductLink id={p.id}><Code>{p.code}</Code></ProductLink> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (p) => p.name },
          { id: 'type', header: t('inv.productType'), hideBelow: 'sm', cell: (p) => enumLabel(p.productType) },
          { id: 'category', header: t('inv.category'), hideBelow: 'md', cell: (p) => <EntityName source={entities.category} id={p.categoryId} /> },
          { id: 'uom', header: t('inv.baseUom'), hideBelow: 'lg', cell: (p) => <EntityName source={entities.uom} id={p.baseUomId} /> },
          { id: 'variants', header: t('inv.variants'), align: 'right', hideBelow: 'lg', cell: (p) => p.variants?.length ?? 0 },
          { id: 'status', header: t('common.status'), cell: (p) => <StatusBadge status={p.status} /> },
        ]}
        toolbar={create}
        emptyAction={create}
      />
      {creating ? (
        <ProductDrawer
          title={t('inv.newProduct')}
          mode="create"
          onClose={() => setCreating(false)}
          onSubmit={async (v) => {
            const product = await api.post('/products', null, { body: compact(v) as Schemas['ProductRequest'] });
            notify.success(t('common.created'));
            await navigate({ to: '/c/$companyId/inventory/products/$productId', params: { companyId, productId: product.id! } });
          }}
        />
      ) : null}
    </div>
  );
}

function ProductDrawer({
  title,
  mode,
  product,
  onClose,
  onSubmit,
}: {
  title: string;
  mode: 'create' | 'edit';
  product?: Product;
  onClose: () => void;
  onSubmit: (values: ProductValues) => Promise<void>;
}) {
  const form = useForm<ProductValues>({ resolver: zodResolver(productSchema), defaultValues: productValues(product) });
  const { submit, ...problem } = useSubmit(form, onSubmit);
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={title}
      wide
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form="product-form">{t('common.save')}</Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id="product-form">
        <ProductFields mode={mode} />
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

const productRoute = getRouteApi('/_authed/c/$companyId/inventory/products/$productId');

const productActions: DocAction<Product>[] = [
  {
    id: 'archive',
    label: t('inv.archive'),
    variant: 'destructive',
    when: (p) => p.status === 'ACTIVE',
    permissions: ['inventory.product.manage'],
    confirm: { title: t('inv.archiveConfirm'), destructive: true },
    run: (api, p) => api.post('/products/{productId}/archive', { productId: p.id! }, { ifMatch: p.version }),
    success: t('common.saved'),
  },
  {
    id: 'unarchive',
    label: t('inv.unarchive'),
    when: (p) => p.status === 'ARCHIVED',
    permissions: ['inventory.product.manage'],
    run: (api, p) => api.post('/products/{productId}/unarchive', { productId: p.id! }, { ifMatch: p.version }),
    success: t('common.saved'),
  },
];

export function ProductPage() {
  const { productId } = productRoute.useParams();
  const { api, can } = useCompany();
  const query = useCompanyQuery(['products', productId], (c, signal) => c.get('/products/{productId}', { productId }, { signal }));
  const [editing, setEditing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const p = query.data;
  const initial = productValues(p);
  return (
    <div className="space-y-4">
      <PageHeader
        title={p.name}
        description={<Code>{p.code}</Code>}
        badge={<StatusBadge status={p.status} />}
        actions={
          <>
            <AuditHistoryButton entityType="product" entityId={p.id} />
            {can('inventory.product.manage') ? (
              <Button variant="outline" onClick={() => setEditing(true)}>
                <Pencil aria-hidden />
                {t('common.edit')}
              </Button>
            ) : null}
            <DocumentActions doc={p} actions={productActions} />
          </>
        }
      />
      <Tabs defaultValue="overview">
        <TabsList className="flex-wrap">
          <TabsTrigger value="overview">{t('common.details')}</TabsTrigger>
          <TabsTrigger value="variants">{t('inv.variants')}</TabsTrigger>
          <TabsTrigger value="conversions">{t('inv.conversions')}</TabsTrigger>
          {can('inventory.stock.read') && p.productType === 'STOCKABLE' ? <TabsTrigger value="stock">{t('inv.stock')}</TabsTrigger> : null}
        </TabsList>
        <TabsContent value="overview">
          <Section>
            <DetailList
              items={[
                { label: t('inv.productType'), value: enumLabel(p.productType) },
                { label: t('inv.category'), value: <EntityName source={entities.category} id={p.categoryId} /> },
                { label: t('inv.baseUom'), value: <EntityName source={entities.uom} id={p.baseUomId} /> },
                { label: t('inv.purchaseUom'), value: <EntityName source={entities.uom} id={p.purchaseUomId} /> },
                { label: t('inv.salesUom'), value: <EntityName source={entities.uom} id={p.salesUomId} /> },
                { label: t('inv.salesTax'), value: <EntityName source={entities.taxCode} id={p.salesTaxCodeId} /> },
                { label: t('inv.purchaseTax'), value: <EntityName source={entities.taxCode} id={p.purchaseTaxCodeId} /> },
                { label: t('inv.purchasable'), value: p.isPurchasable ? t('common.yes') : t('common.no') },
                { label: t('inv.sellable'), value: p.isSellable ? t('common.yes') : t('common.no') },
                { label: t('common.description'), value: <Text value={p.description} />, wide: true },
              ]}
            />
          </Section>
        </TabsContent>
        <TabsContent value="variants">
          <VariantsSection product={p} />
        </TabsContent>
        <TabsContent value="conversions">
          <ConversionsSection product={p} />
        </TabsContent>
        {can('inventory.stock.read') && p.productType === 'STOCKABLE' ? (
          <TabsContent value="stock" className="space-y-4">
            {(p.variants ?? []).map((v) => (
              <Section key={v.id} title={`${v.sku} — ${v.name ?? p.name}`} bodyClassName="p-0">
                <StockLevelsTable variantId={v.id} />
              </Section>
            ))}
          </TabsContent>
        ) : null}
      </Tabs>
      {editing ? (
        <ProductDrawer
          title={t('inv.editProduct')}
          mode="edit"
          product={p}
          onClose={() => setEditing(false)}
          onSubmit={async (v) => {
            const { code: _c, productType: _t, hasVariants: _h, sku: _s, barcode: _b, ...rest } = v;
            const { code: _ic, productType: _it, hasVariants: _ih, sku: _is, barcode: _ib, ...before } = initial;
            await api.patch('/products/{productId}', { productId: p.id! }, { body: mergePatch(before, rest), ifMatch: p.version });
            notify.success(t('common.saved'));
            setEditing(false);
          }}
        />
      ) : null}
    </div>
  );
}

const variantSchema = z.object({ sku: zf.text(40), barcode: zf.optionalText(40), name: zf.optionalText(200), weightKg: zf.optionalDecimal() });

function VariantsSection({ product }: { product: Product }) {
  const { api, can } = useCompany();
  const [editing, setEditing] = useState<{ variant?: Variant } | null>(null);
  const manage = can('inventory.product.manage');
  const variants = product.variants ?? [];
  return (
    <Section title={t('inv.variants')} actions={manage && product.hasVariants ? <Button size="sm" onClick={() => setEditing({})}><Plus aria-hidden />{t('inv.newVariant')}</Button> : null} bodyClassName="p-0">
      {variants.length === 0 ? <EmptyState /> : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('inv.sku')}</TableHead>
                <TableHead>{t('common.name')}</TableHead>
                <TableHead>{t('inv.barcode')}</TableHead>
                <TableHead className="text-right">{t('inv.weightKg')}</TableHead>
                <TableHead>{t('inv.attributes')}</TableHead>
                <TableHead>{t('common.status')}</TableHead>
                <TableHead><span className="sr-only">{t('common.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {variants.map((v) => (
                <TableRow key={v.id}>
                  <TableCell><Code>{v.sku}</Code></TableCell>
                  <TableCell><Text value={v.name} /></TableCell>
                  <TableCell><Text value={v.barcode} /></TableCell>
                  <TableCell className="text-right"><Quantity value={v.weightKg} /></TableCell>
                  <TableCell className="text-xs">{Object.entries((v.attributes ?? {}) as Record<string, string>).map(([k, val]) => `${k}: ${val}`).join(', ') || '—'}</TableCell>
                  <TableCell><StatusBadge status={v.status} /></TableCell>
                  <TableCell className="text-right">
                    {manage ? <Button size="icon-sm" variant="ghost" onClick={() => setEditing({ variant: v })} aria-label={`${t('common.edit')} ${v.sku}`}><Pencil aria-hidden /></Button> : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing ? (
        <FormDialog
          open
          onOpenChange={(open) => !open && setEditing(null)}
          title={editing.variant ? t('inv.editVariant') : t('inv.newVariant')}
          schema={variantSchema}
          defaults={{ sku: editing.variant?.sku ?? '', barcode: editing.variant?.barcode ?? '', name: editing.variant?.name ?? '', weightKg: editing.variant?.weightKg ?? null }}
          success={t('common.saved')}
          onSubmit={(v) =>
            editing.variant
              ? api.patch('/variants/{variantId}', { variantId: editing.variant.id! }, {
                  body: mergePatch({ sku: editing.variant.sku, barcode: editing.variant.barcode, name: editing.variant.name, weightKg: editing.variant.weightKg }, v),
                  ifMatch: editing.variant.version,
                })
              : api.post('/products/{productId}/variants', { productId: product.id! }, { body: { ...(compact(v) as Schemas['VariantRequest']), attributes: {} } })
          }
        >
          <FieldGrid>
            <TextField name="sku" label={t('inv.sku')} required />
            <TextField name="barcode" label={t('inv.barcode')} />
            <TextField name="name" label={t('common.name')} />
            <DecimalField name="weightKg" label={t('inv.weightKg')} />
          </FieldGrid>
        </FormDialog>
      ) : null}
    </Section>
  );
}

const conversionSchema = z.object({ uomId: zf.id(), factorToBase: zf.decimal() });

function ConversionsSection({ product }: { product: Product }) {
  const { api, can } = useCompany();
  const productId = product.id!;
  const query = useCompanyQuery(['products', productId, 'conversions'], (c, signal) => c.get('/products/{productId}/uom-conversions', { productId }, { signal }));
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Schemas['Conversion'] | null>(null);
  const manage = can('inventory.product.manage');
  const rows = query.data?.data ?? [];
  return (
    <Section title={t('inv.conversions')} actions={manage ? <Button size="sm" onClick={() => setAdding(true)}><Plus aria-hidden />{t('inv.newConversion')}</Button> : null}>
      {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState className="py-4" /> : (
        <ul className="divide-y text-sm">
          {rows.map((c) => (
            <li key={c.id} className="flex items-center gap-2 py-2">
              <span className="flex-1">
                1 <EntityName source={entities.uom} id={c.uomId} /> = <Quantity value={c.factorToBase} /> <EntityName source={entities.uom} id={product.baseUomId} />
              </span>
              {manage ? <Button size="icon-sm" variant="ghost" onClick={() => setRemoving(c)} aria-label={t('common.remove')}><Trash2 aria-hidden /></Button> : null}
            </li>
          ))}
        </ul>
      )}
      <FormDialog
        open={adding}
        onOpenChange={setAdding}
        title={t('inv.newConversion')}
        schema={conversionSchema}
        defaults={{ uomId: null, factorToBase: null }}
        success={t('common.saved')}
        onSubmit={(v) => api.post('/products/{productId}/uom-conversions', { productId }, { body: { uomId: v.uomId!, factorToBase: v.factorToBase! } })}
      >
        <EntityField name="uomId" label={t('inv.uom')} source={entities.uom} required />
        <DecimalField name="factorToBase" label={t('inv.factorToBase')} hint={t('inv.conversionHint')} required />
      </FormDialog>
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('common.remove')}
        destructive
        confirmLabel={t('common.remove')}
        onConfirm={async () => {
          await api.delete('/products/{productId}/uom-conversions/{conversionId}', { productId, conversionId: removing!.id! });
          await query.refetch();
        }}
      />
    </Section>
  );
}

// --- Categories, attributes, reason codes -----------------------------------------------------

type Category = Schemas['Category'];
const categorySchema = z.object({ code: zf.text(40), name: zf.text(100), parentId: zf.optionalId() });

export function CategoriesPage() {
  return (
    <MasterDataPage<Category, z.infer<typeof categorySchema>>
      title={t('inv.categoriesTitle')}
      managePermission="inventory.product.manage"
      table={{
        id: 'categories',
        fetchPage: (api, query, signal) => api.get('/product-categories', null, { query, signal }),
        rowKey: (c) => c.id!,
        defaultSort: 'code',
        filters: [{ kind: 'boolean', key: 'isActive', label: t('common.active') }],
        columns: [
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (c) => <Code>{c.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (c) => c.name },
          { id: 'path', header: t('fields.parent'), hideBelow: 'sm', cell: (c) => <Text value={c.path} /> },
          { id: 'active', header: t('common.status'), cell: (c) => activeBadge(c.isActive) },
        ],
      }}
      form={{
        schema: categorySchema,
        values: (c) => ({ code: c?.code ?? '', name: c?.name ?? '', parentId: c?.parentId ?? null }),
        createTitle: t('inv.newCategory'),
        editTitle: (c) => t('inv.editCategory', { code: c.code }),
        fields: (mode, c) => (
          <>
            <FieldGrid>
              <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
              <TextField name="name" label={t('common.name')} required />
            </FieldGrid>
            <EntityField name="parentId" label={t('fields.parent')} source={entities.category} filter={(x) => x.id !== c?.id} />
          </>
        ),
        create: (api, v) => api.post('/product-categories', null, { body: compact(v) as Schemas['CategoryRequest'] }),
        update: (api, c, v) =>
          api.patch('/product-categories/{categoryId}', { categoryId: c.id! }, { body: mergePatch({ name: c.name, parentId: c.parentId }, { name: v.name, parentId: v.parentId }), ifMatch: c.version }),
      }}
      rowActions={activationActions<Category>(
        (c) => c.isActive !== false,
        (c) => ({
          activate: (api) => api.post('/product-categories/{categoryId}/activate', { categoryId: c.id! }, { ifMatch: c.version }),
          deactivate: (api) => api.post('/product-categories/{categoryId}/deactivate', { categoryId: c.id! }, { ifMatch: c.version }),
        }),
        'inventory.product.manage',
      )}
    />
  );
}

type Attribute = Schemas['Attribute'];
const attributeSchema = z.object({
  code: zf.text(40),
  name: zf.text(100),
  values: z.array(z.object({ code: zf.text(40), name: zf.text(100), sortOrder: zf.integer() })),
});
type AttributeValues = z.infer<typeof attributeSchema>;

export function AttributesPage() {
  const { api, can } = useCompany();
  const [creating, setCreating] = useState(false);
  const [addingTo, setAddingTo] = useState<Attribute | null>(null);
  return (
    <div className="space-y-4">
      <PageHeader title={t('inv.attributesTitle')} />
      <DataTable<Attribute>
        id="attributes"
        fetchPage={(c, query, signal) => c.get('/product-attributes', null, { query, signal })}
        rowKey={(a) => a.id!}
        defaultSort="code"
        columns={[
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (a) => <Code>{a.code}</Code> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (a) => a.name },
          { id: 'values', header: t('inv.values'), cell: (a) => (a.values ?? []).map((v) => v.name).join(', ') || '—' },
          {
            id: 'actions',
            header: <span className="sr-only">{t('common.actions')}</span>,
            align: 'right',
            cell: (a) => (can('inventory.product.manage') ? <Button size="sm" variant="ghost" onClick={() => setAddingTo(a)}><Plus aria-hidden />{t('inv.addValue')}</Button> : null),
          },
        ]}
        toolbar={can('inventory.product.manage') ? <Button onClick={() => setCreating(true)}><Plus aria-hidden />{t('inv.newAttribute')}</Button> : null}
      />
      {creating ? <CreateAttributeDrawer onClose={() => setCreating(false)} /> : null}
      <FormDialog
        open={addingTo !== null}
        onOpenChange={(open) => !open && setAddingTo(null)}
        title={t('inv.addValue')}
        description={addingTo?.name}
        schema={z.object({ code: zf.text(40), name: zf.text(100), sortOrder: zf.integer() })}
        defaults={{ code: '', name: '', sortOrder: (addingTo?.values?.length ?? 0) + 1 }}
        success={t('common.saved')}
        onSubmit={(v) => api.post('/product-attributes/{attributeId}/values', { attributeId: addingTo!.id! }, { body: v })}
      >
        <FieldGrid columns={3}>
          <TextField name="code" label={t('common.code')} required />
          <TextField name="name" label={t('common.name')} required />
          <IntegerField name="sortOrder" label={t('inv.sortOrder')} required />
        </FieldGrid>
      </FormDialog>
    </div>
  );
}

function CreateAttributeDrawer({ onClose }: { onClose: () => void }) {
  const { api } = useCompany();
  const form = useForm<AttributeValues>({ resolver: zodResolver(attributeSchema), defaultValues: { code: '', name: '', values: [{ code: '', name: '', sortOrder: 1 }] } });
  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'values' });
  const { submit, ...problem } = useSubmit(form, async (v) => {
    await api.post('/product-attributes', null, { body: v });
    notify.success(t('common.created'));
    onClose();
  });
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('inv.newAttribute')}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" form="attribute-form">{t('common.save')}</Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id="attribute-form">
        <FieldGrid>
          <TextField name="code" label={t('common.code')} required />
          <TextField name="name" label={t('common.name')} required />
        </FieldGrid>
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">{t('inv.values')}</legend>
          {fields.map((field, index) => (
            <div key={field.id} className="flex items-center gap-2">
              <Input {...form.register(`values.${index}.code`)} placeholder={t('common.code')} aria-label={`${t('common.code')} ${index + 1}`} />
              <Input {...form.register(`values.${index}.name`)} placeholder={t('common.name')} aria-label={`${t('common.name')} ${index + 1}`} />
              <Button type="button" size="icon-sm" variant="ghost" onClick={() => remove(index)} aria-label={`${t('common.remove')} ${index + 1}`}><Trash2 aria-hidden /></Button>
            </div>
          ))}
          <Button type="button" size="sm" variant="outline" onClick={() => append({ code: '', name: '', sortOrder: fields.length + 1 })}>
            <Plus aria-hidden />
            {t('inv.addValue')}
          </Button>
        </fieldset>
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

type ReasonCode = Schemas['ReasonCode'];
const reasonSchema = z.object({ code: zf.text(20), name: zf.text(100), appliesTo: zf.id() });

export function ReasonCodesPage() {
  return (
    <MasterDataPage<ReasonCode, z.infer<typeof reasonSchema>>
      title={t('inv.reasonCodesTitle')}
      managePermission="inventory.adjustment.manage"
      table={{
        id: 'reason-codes',
        fetchPage: (api, query, signal) => api.get('/reason-codes', null, { query, signal }),
        rowKey: (r) => r.id!,
        searchable: false,
        filters: [{ kind: 'enum', key: 'appliesTo', label: t('fields.appliesTo'), values: [...enums.reasonAppliesTo] }],
        columns: [
          { id: 'code', header: t('common.code'), cell: (r) => <Code>{r.code}</Code> },
          { id: 'name', header: t('common.name'), cell: (r) => r.name },
          { id: 'applies', header: t('fields.appliesTo'), cell: (r) => enumLabel(r.appliesTo) },
          { id: 'active', header: t('common.status'), cell: (r) => activeBadge(r.isActive) },
        ],
      }}
      form={{
        schema: reasonSchema,
        values: () => ({ code: '', name: '', appliesTo: 'ADJUSTMENT' }),
        createTitle: t('inv.newReasonCode'),
        editTitle: () => '',
        fields: () => (
          <>
            <TextField name="code" label={t('common.code')} required />
            <TextField name="name" label={t('common.name')} required />
            <SelectField name="appliesTo" label={t('fields.appliesTo')} options={enumOptions(enums.reasonAppliesTo)} required />
          </>
        ),
        create: (api, v) => api.post('/reason-codes', null, { body: v as Schemas['ReasonCodeRequest'] }),
      }}
    />
  );
}
