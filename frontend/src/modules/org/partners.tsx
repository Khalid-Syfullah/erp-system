import { zodResolver } from '@hookform/resolvers/zod';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { Eye, Pencil, Plus, Trash2 } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useForm, type FieldValues } from 'react-hook-form';
import { z } from 'zod';
import type { CompanyApi, Schemas } from '@/api/client';
import { useCompanyQuery } from '@/api/hooks';
import { useCompany } from '@/auth/company';
import { DetailList, PageHeader, Section } from '@/components/common/page';
import { StatusBadge } from '@/components/common/status-badge';
import { Code, Money, Text } from '@/components/common/values';
import { DataTable, type Column, type FilterDef } from '@/components/data/data-table';
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
import { ConfirmDialog } from '@/components/overlay/confirm-dialog';
import { Drawer } from '@/components/overlay/drawer';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { enumLabel, t } from '@/i18n';
import { enumOptions, enums } from '@/lib/enums';

type Partner = Schemas['Partner'];

const partnerSchema = z.object({
  code: zf.text(20),
  name: zf.text(200),
  legalName: zf.optionalText(200),
  partnerType: zf.id(),
  taxRegistrationNo: zf.optionalText(50),
  email: zf.optionalEmail(),
  phone: zf.optionalText(50),
  website: zf.optionalText(200),
  notes: zf.optionalText(2000),
});
type PartnerValues = z.infer<typeof partnerSchema>;

function partnerValues(p?: Partner): PartnerValues {
  return {
    code: p?.code ?? '',
    name: p?.name ?? '',
    legalName: p?.legalName ?? '',
    partnerType: p?.partnerType ?? 'ORGANIZATION',
    taxRegistrationNo: p?.taxRegistrationNo ?? '',
    email: p?.email ?? '',
    phone: p?.phone ?? '',
    website: p?.website ?? '',
    notes: p?.notes ?? '',
  };
}

function PartnerFields({ mode }: { mode: 'create' | 'edit' }) {
  return (
    <>
      <FieldGrid>
        <TextField name="code" label={t('common.code')} required disabled={mode === 'edit'} />
        <SelectField name="partnerType" label={t('fields.partnerType')} options={enumOptions(enums.partnerType)} required disabled={mode === 'edit'} />
        <TextField name="name" label={t('common.name')} required />
        <TextField name="legalName" label={t('fields.legalName')} />
        <TextField name="taxRegistrationNo" label={t('fields.taxRegistrationNo')} />
        <TextField name="email" label={t('fields.email')} type="email" />
        <TextField name="phone" label={t('fields.phone')} type="tel" />
        <TextField name="website" label={t('fields.website')} />
      </FieldGrid>
      <TextareaField name="notes" label={t('common.notes')} />
    </>
  );
}

const partnerStatusFilter: FilterDef = { kind: 'enum', key: 'status', label: t('common.status'), values: [...enums.partnerStatus] };

function PartnerLink({ id, children }: { id: string; children: ReactNode }) {
  const { companyId } = useCompany();
  return (
    <Link to="/c/$companyId/org/partners/$partnerId" params={{ companyId, partnerId: id }} data-row-link className="font-medium text-primary hover:underline">
      {children}
    </Link>
  );
}

export function PartnersPage() {
  const { can } = useCompany();
  const [creating, setCreating] = useState(false);
  const columns: Column<Partner>[] = [
    { id: 'code', header: t('common.code'), sortKey: 'code', cell: (p) => <PartnerLink id={p.id!}><Code>{p.code}</Code></PartnerLink> },
    { id: 'name', header: t('common.name'), sortKey: 'name', cell: (p) => p.name },
    {
      id: 'roles',
      header: t('org.roles'),
      cell: (p) => (
        <span className="flex gap-1">
          {p.isCustomer ? <StatusBadge status="OPEN" label={t('org.customer')} /> : null}
          {p.isSupplier ? <StatusBadge status="OPEN" tone="neutral" label={t('org.supplier')} /> : null}
        </span>
      ),
    },
    { id: 'type', header: t('fields.partnerType'), hideBelow: 'md', cell: (p) => enumLabel(p.partnerType) },
    { id: 'tax', header: t('fields.taxRegistrationNo'), hideBelow: 'lg', cell: (p) => <Text value={p.taxRegistrationNo} /> },
    { id: 'status', header: t('common.status'), cell: (p) => <StatusBadge status={p.status} /> },
  ];
  const create = can('partners.partner.manage') ? (
    <Button onClick={() => setCreating(true)}>
      <Plus aria-hidden />
      {t('org.newPartner')}
    </Button>
  ) : null;
  return (
    <div className="space-y-4">
      <PageHeader title={t('org.partnersTitle')} />
      <DataTable
        id="partners"
        fetchPage={(api, query, signal) => api.get('/partners', null, { query, signal })}
        columns={columns}
        rowKey={(p) => p.id!}
        defaultSort="code"
        filters={[partnerStatusFilter, { kind: 'enum', key: 'partnerType', label: t('fields.partnerType'), values: [...enums.partnerType] }]}
        toolbar={create}
        emptyAction={create}
      />
      {creating ? <CreatePartnerDrawer onClose={() => setCreating(false)} /> : null}
    </div>
  );
}

function CreatePartnerDrawer({ onClose }: { onClose: () => void }) {
  const { api, companyId } = useCompany();
  const navigate = useNavigate();
  const form = useForm<PartnerValues>({ resolver: zodResolver(partnerSchema), defaultValues: partnerValues() });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    const partner = await api.post('/partners', null, { body: compact(values) as Schemas['PartnerRequest'] });
    notify.success(t('common.created'));
    await navigate({ to: '/c/$companyId/org/partners/$partnerId', params: { companyId, partnerId: partner.id! } });
  });
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('org.newPartner')}
      wide
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="partner-create">
            {t('common.create')}
          </Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id="partner-create">
        <PartnerFields mode="create" />
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

/** Customers: partners with a customer profile (credit limit in the base currency). */
export function CustomersPage() {
  return (
    <div className="space-y-4">
      <PageHeader title={t('org.customersTitle')} />
      <DataTable<Schemas['CustomerRow']>
        id="customers"
        fetchPage={(api, query, signal) => api.get('/customers', null, { query, signal })}
        rowKey={(c) => c.id!}
        defaultSort="code"
        filters={[
          partnerStatusFilter,
          { kind: 'boolean', key: 'isOnHold', label: t('fields.onHold') },
          { kind: 'entity', key: 'currencyCode', label: t('common.currency'), source: entities.currency },
        ]}
        columns={[
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (c) => <PartnerLink id={c.id!}><Code>{c.code}</Code></PartnerLink> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (c) => c.name },
          { id: 'currency', header: t('common.currency'), cell: (c) => c.profile?.currencyCode },
          { id: 'terms', header: t('fields.paymentTerms'), hideBelow: 'md', cell: (c) => <EntityName source={entities.paymentTerms} id={c.profile?.paymentTermsId} /> },
          { id: 'limit', header: t('fields.creditLimit'), align: 'right', hideBelow: 'sm', cell: (c) => <Money value={c.profile?.creditLimit} showCurrency={false} /> },
          { id: 'hold', header: t('fields.onHold'), hideBelow: 'sm', cell: (c) => (c.profile?.isOnHold ? <StatusBadge status="ON_HOLD" label={t('fields.onHold')} /> : null) },
          { id: 'status', header: t('common.status'), cell: (c) => <StatusBadge status={c.status} /> },
        ]}
      />
    </div>
  );
}

export function SuppliersPage() {
  return (
    <div className="space-y-4">
      <PageHeader title={t('org.suppliersTitle')} />
      <DataTable<Schemas['SupplierRow']>
        id="suppliers"
        fetchPage={(api, query, signal) => api.get('/suppliers', null, { query, signal })}
        rowKey={(c) => c.id!}
        defaultSort="code"
        filters={[partnerStatusFilter, { kind: 'entity', key: 'currencyCode', label: t('common.currency'), source: entities.currency }]}
        columns={[
          { id: 'code', header: t('common.code'), sortKey: 'code', cell: (c) => <PartnerLink id={c.id!}><Code>{c.code}</Code></PartnerLink> },
          { id: 'name', header: t('common.name'), sortKey: 'name', cell: (c) => c.name },
          { id: 'currency', header: t('common.currency'), cell: (c) => c.profile?.currencyCode },
          { id: 'terms', header: t('fields.paymentTerms'), hideBelow: 'md', cell: (c) => <EntityName source={entities.paymentTerms} id={c.profile?.paymentTermsId} /> },
          { id: 'lead', header: t('fields.leadTimeDays'), align: 'right', hideBelow: 'sm', cell: (c) => <Text value={c.profile?.leadTimeDays} /> },
          { id: 'status', header: t('common.status'), cell: (c) => <StatusBadge status={c.status} /> },
        ]}
      />
    </div>
  );
}

// --- Partner detail ---------------------------------------------------------------------------

const route = getRouteApi('/_authed/c/$companyId/org/partners/$partnerId');

const partnerActions: DocAction<Partner>[] = [
  {
    id: 'block',
    label: t('org.block'),
    variant: 'destructive',
    when: (p) => p.status === 'ACTIVE',
    permissions: ['partners.partner.manage'],
    confirm: { title: t('org.blockConfirm'), destructive: true },
    run: (api, p) => api.post('/partners/{partnerId}/block', { partnerId: p.id! }, { ifMatch: p.version }),
    success: t('common.saved'),
  },
  {
    id: 'deactivate',
    label: t('common.deactivate'),
    when: (p) => p.status === 'ACTIVE',
    permissions: ['partners.partner.manage'],
    run: (api, p) => api.post('/partners/{partnerId}/deactivate', { partnerId: p.id! }, { ifMatch: p.version }),
    success: t('common.saved'),
  },
  {
    id: 'activate',
    label: t('common.activate'),
    when: (p) => p.status !== 'ACTIVE',
    permissions: ['partners.partner.manage'],
    run: (api, p) => api.post('/partners/{partnerId}/activate', { partnerId: p.id! }, { ifMatch: p.version }),
    success: t('common.saved'),
  },
];

export function PartnerPage() {
  const { partnerId } = route.useParams();
  const { can } = useCompany();
  const query = useCompanyQuery(['partners', partnerId], (api, signal) => api.get('/partners/{partnerId}', { partnerId }, { signal }));
  const [editing, setEditing] = useState(false);
  if (query.isLoading) return <LoadingState />;
  if (query.isError || !query.data) return <ErrorState error={query.error} onRetry={() => query.refetch()} />;
  const p = query.data;
  return (
    <div className="space-y-4">
      <PageHeader
        title={p.name}
        description={<Code>{p.code}</Code>}
        badge={<StatusBadge status={p.status} />}
        actions={
          <>
            <AuditHistoryButton entityType="partner" entityId={p.id} />
            {can('partners.partner.manage') ? (
              <Button variant="outline" onClick={() => setEditing(true)}>
                <Pencil aria-hidden />
                {t('common.edit')}
              </Button>
            ) : null}
            <DocumentActions doc={p} actions={partnerActions} />
          </>
        }
      />
      <Tabs defaultValue="overview">
        <TabsList className="flex-wrap">
          <TabsTrigger value="overview">{t('common.details')}</TabsTrigger>
          <TabsTrigger value="addresses">{t('org.addresses')}</TabsTrigger>
          <TabsTrigger value="contacts">{t('org.contacts')}</TabsTrigger>
          {can('partners.partner.read_bank') ? <TabsTrigger value="bank">{t('org.bankAccounts')}</TabsTrigger> : null}
          <TabsTrigger value="profiles">{t('org.roles')}</TabsTrigger>
        </TabsList>
        <TabsContent value="overview">
          <Section>
            <DetailList
              items={[
                { label: t('fields.partnerType'), value: enumLabel(p.partnerType) },
                { label: t('fields.legalName'), value: <Text value={p.legalName} /> },
                { label: t('fields.taxRegistrationNo'), value: <Text value={p.taxRegistrationNo} /> },
                { label: t('fields.email'), value: <Text value={p.email} /> },
                { label: t('fields.phone'), value: <Text value={p.phone} /> },
                { label: t('fields.website'), value: <Text value={p.website} /> },
                { label: t('common.notes'), value: <Text value={p.notes} />, wide: true },
              ]}
            />
          </Section>
        </TabsContent>
        <TabsContent value="addresses">
          <AddressesSection partner={p} />
        </TabsContent>
        <TabsContent value="contacts">
          <ContactsSection partner={p} />
        </TabsContent>
        {can('partners.partner.read_bank') ? (
          <TabsContent value="bank">
            <BankAccountsSection partner={p} />
          </TabsContent>
        ) : null}
        <TabsContent value="profiles" className="grid gap-4 lg:grid-cols-2">
          <SupplierProfileSection partner={p} />
          <CustomerProfileSection partner={p} />
        </TabsContent>
      </Tabs>
      {editing ? <EditPartnerDrawer partner={p} onClose={() => setEditing(false)} /> : null}
    </div>
  );
}

function EditPartnerDrawer({ partner, onClose }: { partner: Partner; onClose: () => void }) {
  const { api } = useCompany();
  const initial = partnerValues(partner);
  const form = useForm<PartnerValues>({ resolver: zodResolver(partnerSchema), defaultValues: initial });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    const { code: _c, partnerType: _t, ...rest } = values;
    const { code: _ic, partnerType: _it, ...before } = initial;
    await api.patch('/partners/{partnerId}', { partnerId: partner.id! }, { body: mergePatch(before, rest), ifMatch: partner.version });
    notify.success(t('common.saved'));
    onClose();
  });
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('org.editPartner')}
      wide
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="partner-edit">
            {t('common.save')}
          </Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id="partner-edit">
        <PartnerFields mode="edit" />
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

/** A drawer form for a sub-record (address, contact, bank account, profile). */
function SubForm<V extends FieldValues>({
  title,
  schema,
  defaults,
  onSubmit,
  onClose,
  children,
}: {
  title: string;
  schema: z.ZodType<V, V>;
  defaults: V;
  onSubmit: (values: V) => Promise<unknown>;
  onClose: () => void;
  children: ReactNode;
}) {
  const form = useForm<V>({ resolver: zodResolver(schema as never) as never, defaultValues: defaults as never });
  const { submit, ...problem } = useSubmit(form, async (values) => {
    await onSubmit(values);
    notify.success(t('common.saved'));
    onClose();
  });
  return (
    <Drawer
      open
      onOpenChange={(open) => !open && onClose()}
      title={title}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="sub-form">
            {t('common.save')}
          </Button>
        </div>
      }
    >
      <Form form={form} onSubmit={submit} id="sub-form">
        {children}
        <FormProblem {...problem} />
      </Form>
    </Drawer>
  );
}

const addressSchema = z.object({
  addressType: zf.id(),
  line1: zf.text(200),
  line2: zf.optionalText(200),
  city: zf.optionalText(100),
  region: zf.optionalText(100),
  postalCode: zf.optionalText(20),
  countryCode: zf.optionalId(),
  isDefault: z.boolean(),
});
type AddressValues = z.infer<typeof addressSchema>;
type PartnerAddress = Schemas['PartnersAddress'] & { id?: string; addressType?: string; isDefault?: boolean; version?: number };

function AddressesSection({ partner }: { partner: Partner }) {
  const { api, can } = useCompany();
  const partnerId = partner.id!;
  const query = useCompanyQuery(['partners', partnerId, 'addresses'], (c, signal) => c.get('/partners/{partnerId}/addresses', { partnerId }, { signal }));
  const [editing, setEditing] = useState<{ address?: PartnerAddress } | null>(null);
  const [removing, setRemoving] = useState<PartnerAddress | null>(null);
  const manage = can('partners.partner.manage');
  const rows = (query.data?.data ?? []) as PartnerAddress[];
  return (
    <Section
      title={t('org.addresses')}
      actions={manage ? <Button size="sm" onClick={() => setEditing({})}><Plus aria-hidden />{t('org.newAddress')}</Button> : null}
    >
      {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState /> : (
        <ul className="grid gap-3 sm:grid-cols-2">
          {rows.map((a) => (
            <li key={a.id} className="space-y-1 rounded-lg border p-3 text-sm">
              <div className="flex items-center gap-2">
                <StatusBadge status={a.addressType} tone="info" />
                {a.isDefault ? <StatusBadge status="ACTIVE" label={t('fields.isDefault')} /> : null}
                {manage ? (
                  <span className="ml-auto flex gap-1">
                    <Button size="icon-sm" variant="ghost" onClick={() => setEditing({ address: a })} aria-label={t('common.edit')}><Pencil aria-hidden /></Button>
                    <Button size="icon-sm" variant="ghost" onClick={() => setRemoving(a)} aria-label={t('common.remove')}><Trash2 aria-hidden /></Button>
                  </span>
                ) : null}
              </div>
              <div>{a.line1}</div>
              {a.line2 ? <div>{a.line2}</div> : null}
              <div className="text-muted-foreground">{[a.postalCode, a.city, a.region, a.countryCode].filter(Boolean).join(', ')}</div>
            </li>
          ))}
        </ul>
      )}
      {editing ? (
        <SubForm<AddressValues>
          title={editing.address ? t('common.edit') : t('org.newAddress')}
          schema={addressSchema}
          defaults={{
            addressType: editing.address?.addressType ?? 'BILLING',
            line1: editing.address?.line1 ?? '',
            line2: editing.address?.line2 ?? '',
            city: editing.address?.city ?? '',
            region: editing.address?.region ?? '',
            postalCode: editing.address?.postalCode ?? '',
            countryCode: editing.address?.countryCode ?? null,
            isDefault: editing.address?.isDefault ?? false,
          }}
          onClose={() => setEditing(null)}
          onSubmit={(v) =>
            editing.address
              ? api.patch('/partners/{partnerId}/addresses/{addressId}', { partnerId, addressId: editing.address.id! }, {
                  body: mergePatch(
                    { line1: editing.address.line1, line2: editing.address.line2, city: editing.address.city, region: editing.address.region, postalCode: editing.address.postalCode, countryCode: editing.address.countryCode, isDefault: editing.address.isDefault, addressType: editing.address.addressType },
                    v,
                  ),
                  ifMatch: editing.address.version,
                })
              : api.post('/partners/{partnerId}/addresses', { partnerId }, { body: compact(v) as Schemas['AddressRequest'] })
          }
        >
          <SelectField name="addressType" label={t('fields.addressType')} options={enumOptions(enums.addressType)} required />
          <TextField name="line1" label={t('fields.addressLine1')} required />
          <TextField name="line2" label={t('fields.addressLine2')} />
          <FieldGrid>
            <TextField name="city" label={t('fields.city')} />
            <TextField name="region" label={t('fields.region')} />
            <TextField name="postalCode" label={t('fields.postalCode')} />
            <EntityField name="countryCode" label={t('fields.country')} source={entities.country} />
          </FieldGrid>
          <CheckboxField name="isDefault" label={t('fields.isDefault')} />
        </SubForm>
      ) : null}
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('org.removeConfirm', { name: removing?.line1 ?? '' })}
        destructive
        confirmLabel={t('common.remove')}
        onConfirm={async () => {
          await api.delete('/partners/{partnerId}/addresses/{addressId}', { partnerId, addressId: removing!.id! });
          await query.refetch();
        }}
      />
    </Section>
  );
}

const contactSchema = z.object({
  name: zf.text(200),
  email: zf.optionalEmail(),
  phone: zf.optionalText(50),
  roleTitle: zf.optionalText(100),
  isPrimary: z.boolean(),
});
type ContactValues = z.infer<typeof contactSchema>;

function ContactsSection({ partner }: { partner: Partner }) {
  const { api, can } = useCompany();
  const partnerId = partner.id!;
  const query = useCompanyQuery(['partners', partnerId, 'contacts'], (c, signal) => c.get('/partners/{partnerId}/contacts', { partnerId }, { signal }));
  const [editing, setEditing] = useState<{ contact?: Schemas['Contact'] } | null>(null);
  const [removing, setRemoving] = useState<Schemas['Contact'] | null>(null);
  const manage = can('partners.partner.manage');
  const rows = query.data?.data ?? [];
  return (
    <Section title={t('org.contacts')} actions={manage ? <Button size="sm" onClick={() => setEditing({})}><Plus aria-hidden />{t('org.newContact')}</Button> : null}>
      {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState /> : (
        <ul className="divide-y">
          {rows.map((c) => (
            <li key={c.id} className="flex flex-wrap items-center gap-3 py-2 text-sm">
              <div className="min-w-0 flex-1">
                <div className="font-medium">
                  {c.name} {c.isPrimary ? <StatusBadge status="ACTIVE" label={t('fields.primary')} /> : null}
                </div>
                <div className="text-muted-foreground">{[c.roleTitle, c.email, c.phone].filter(Boolean).join(' · ')}</div>
              </div>
              {manage ? (
                <>
                  <Button size="icon-sm" variant="ghost" onClick={() => setEditing({ contact: c })} aria-label={`${t('common.edit')} ${c.name}`}><Pencil aria-hidden /></Button>
                  <Button size="icon-sm" variant="ghost" onClick={() => setRemoving(c)} aria-label={`${t('common.remove')} ${c.name}`}><Trash2 aria-hidden /></Button>
                </>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {editing ? (
        <SubForm<ContactValues>
          title={editing.contact ? t('common.edit') : t('org.newContact')}
          schema={contactSchema}
          defaults={{
            name: editing.contact?.name ?? '',
            email: editing.contact?.email ?? '',
            phone: editing.contact?.phone ?? '',
            roleTitle: editing.contact?.roleTitle ?? '',
            isPrimary: editing.contact?.isPrimary ?? false,
          }}
          onClose={() => setEditing(null)}
          onSubmit={(v) =>
            editing.contact
              ? api.patch('/partners/{partnerId}/contacts/{contactId}', { partnerId, contactId: editing.contact.id! }, {
                  body: mergePatch({ name: editing.contact.name, email: editing.contact.email, phone: editing.contact.phone, roleTitle: editing.contact.roleTitle, isPrimary: editing.contact.isPrimary }, v),
                  ifMatch: editing.contact.version,
                })
              : api.post('/partners/{partnerId}/contacts', { partnerId }, { body: compact(v) as Schemas['ContactRequest'] })
          }
        >
          <TextField name="name" label={t('fields.contactName')} required />
          <FieldGrid>
            <TextField name="email" label={t('fields.email')} type="email" />
            <TextField name="phone" label={t('fields.phone')} type="tel" />
          </FieldGrid>
          <TextField name="roleTitle" label={t('fields.roleTitle')} />
          <CheckboxField name="isPrimary" label={t('fields.primary')} />
        </SubForm>
      ) : null}
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('org.removeConfirm', { name: removing?.name ?? '' })}
        destructive
        confirmLabel={t('common.remove')}
        onConfirm={async () => {
          await api.delete('/partners/{partnerId}/contacts/{contactId}', { partnerId, contactId: removing!.id! });
          await query.refetch();
        }}
      />
    </Section>
  );
}

const bankSchema = z.object({
  bankName: zf.text(100),
  accountHolder: zf.optionalText(200),
  accountNumber: zf.optionalText(40),
  iban: zf.optionalText(40),
  swiftBic: zf.optionalText(11),
  currencyCode: zf.optionalId(),
  isDefault: z.boolean(),
});
type BankValues = z.infer<typeof bankSchema>;

/** Bank details are listed masked; revealing the full number needs step-up and is audited (SECURITY.md §7). */
function BankAccountsSection({ partner }: { partner: Partner }) {
  const { api, can } = useCompany();
  const partnerId = partner.id!;
  const query = useCompanyQuery(['partners', partnerId, 'bank-accounts'], (c, signal) => c.get('/partners/{partnerId}/bank-accounts', { partnerId }, { signal }));
  const [adding, setAdding] = useState(false);
  const [revealed, setRevealed] = useState<Record<string, Schemas['PartnersRevealedBankAccount']>>({});
  const [removing, setRemoving] = useState<Schemas['PartnersBankAccount'] | null>(null);
  const manage = can('partners.partner.manage_bank');
  const rows = query.data?.data ?? [];
  const reveal = async (id: string) => {
    try {
      const result = await api.post('/partners/{partnerId}/bank-accounts/{accountId}/reveal', { partnerId, accountId: id });
      setRevealed((r) => ({ ...r, [id]: result }));
    } catch (e) {
      notify.error(e);
    }
  };
  return (
    <Section title={t('org.bankAccounts')} actions={manage ? <Button size="sm" onClick={() => setAdding(true)}><Plus aria-hidden />{t('org.newBankAccount')}</Button> : null}>
      {query.isLoading ? <LoadingState /> : query.isError ? <ErrorState error={query.error} /> : rows.length === 0 ? <EmptyState /> : (
        <ul className="divide-y">
          {rows.map((b) => (
            <li key={b.id} className="flex flex-wrap items-center gap-3 py-2 text-sm">
              <div className="min-w-0 flex-1">
                <div className="font-medium">
                  {b.bankName} {b.isDefault ? <StatusBadge status="ACTIVE" label={t('fields.isDefault')} /> : null}
                </div>
                <div className="font-mono text-xs text-muted-foreground">
                  {revealed[b.id!] ? [revealed[b.id!]!.accountNumber, revealed[b.id!]!.iban].filter(Boolean).join(' · ') : b.accountNumberMasked}
                  {b.currencyCode ? ` · ${b.currencyCode}` : ''}
                </div>
                <div className="text-xs text-muted-foreground">{b.accountHolder}</div>
              </div>
              {!revealed[b.id!] ? (
                <Button size="sm" variant="ghost" onClick={() => void reveal(b.id!)}>
                  <Eye aria-hidden />
                  {t('common.reveal')}
                </Button>
              ) : null}
              {manage ? (
                <Button size="icon-sm" variant="ghost" onClick={() => setRemoving(b)} aria-label={`${t('common.remove')} ${b.bankName}`}>
                  <Trash2 aria-hidden />
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {adding ? (
        <SubForm<BankValues>
          title={t('org.newBankAccount')}
          schema={bankSchema}
          defaults={{ bankName: '', accountHolder: partner.legalName ?? partner.name ?? '', accountNumber: '', iban: '', swiftBic: '', currencyCode: null, isDefault: false }}
          onClose={() => setAdding(false)}
          onSubmit={(v) => api.post('/partners/{partnerId}/bank-accounts', { partnerId }, { body: compact(v) as Schemas['BankAccountBankAccountRequest'] })}
        >
          <TextField name="bankName" label={t('fields.bankName')} required />
          <TextField name="accountHolder" label={t('fields.accountHolder')} />
          <FieldGrid>
            <TextField name="accountNumber" label={t('fields.accountNumber')} />
            <TextField name="iban" label={t('fields.iban')} />
            <TextField name="swiftBic" label={t('fields.swiftBic')} />
            <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} />
          </FieldGrid>
          <CheckboxField name="isDefault" label={t('fields.isDefault')} />
        </SubForm>
      ) : null}
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('org.removeConfirm', { name: removing?.bankName ?? '' })}
        destructive
        confirmLabel={t('common.remove')}
        onConfirm={async () => {
          await api.delete('/partners/{partnerId}/bank-accounts/{accountId}', { partnerId, accountId: removing!.id! });
          await query.refetch();
        }}
      />
    </Section>
  );
}

const supplierSchema = z.object({
  currencyCode: zf.id(),
  supplierGroupId: zf.optionalId(),
  paymentTermsId: zf.optionalId(),
  defaultTaxCodeId: zf.optionalId(),
  leadTimeDays: zf.optionalInteger(),
});
type SupplierValues = z.infer<typeof supplierSchema>;

/** PUT …/supplier-profile with If-Match: W/"0" creates the profile (API.md §17.4). */
function ProfileSection<V extends FieldValues>({
  title,
  createLabel,
  exists,
  permission,
  details,
  schema,
  defaults,
  save,
  children,
}: {
  title: string;
  createLabel: string;
  exists: boolean;
  permission: string;
  details: ReactNode;
  schema: z.ZodType<V, V>;
  defaults: V;
  save: (api: CompanyApi, values: V) => Promise<unknown>;
  children: ReactNode;
}) {
  const { api, can } = useCompany();
  const [editing, setEditing] = useState(false);
  return (
    <Section
      title={title}
      actions={
        can(permission) ? (
          <Button size="sm" variant="outline" onClick={() => setEditing(true)}>
            {exists ? <><Pencil aria-hidden />{t('common.edit')}</> : <><Plus aria-hidden />{createLabel}</>}
          </Button>
        ) : null
      }
    >
      {exists ? details : <p className="text-sm text-muted-foreground">{t('org.noProfile')}</p>}
      {editing ? (
        <SubForm<V> title={title} schema={schema} defaults={defaults} onClose={() => setEditing(false)} onSubmit={(v) => save(api, v)}>
          {children}
        </SubForm>
      ) : null}
    </Section>
  );
}

function SupplierProfileSection({ partner }: { partner: Partner }) {
  const profile = partner.supplierProfile;
  return (
    <ProfileSection<SupplierValues>
      title={t('org.supplierProfile')}
      createLabel={t('org.makeSupplier')}
      exists={!!partner.isSupplier}
      permission="partners.supplier.manage"
      details={
        <DetailList
          columns={2}
          items={[
            { label: t('common.currency'), value: profile?.currencyCode },
            { label: t('fields.paymentTerms'), value: <EntityName source={entities.paymentTerms} id={profile?.paymentTermsId} /> },
            { label: t('fields.defaultTaxCode'), value: <EntityName source={entities.taxCode} id={profile?.defaultTaxCodeId} /> },
            { label: t('fields.group'), value: <EntityName source={entities.partnerGroup} id={profile?.supplierGroupId} /> },
            { label: t('fields.leadTimeDays'), value: <Text value={profile?.leadTimeDays} /> },
          ]}
        />
      }
      schema={supplierSchema}
      defaults={{
        currencyCode: profile?.currencyCode ?? null,
        supplierGroupId: profile?.supplierGroupId ?? null,
        paymentTermsId: profile?.paymentTermsId ?? null,
        defaultTaxCodeId: profile?.defaultTaxCodeId ?? null,
        leadTimeDays: profile?.leadTimeDays ?? null,
      }}
      save={(api, v) =>
        api.put('/partners/{partnerId}/supplier-profile', { partnerId: partner.id! }, { body: compact(v) as Schemas['SupplierProfileRequest'], ifMatch: profile?.version ?? 0 })
      }
    >
      <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} required />
      <EntityField name="paymentTermsId" label={t('fields.paymentTerms')} source={entities.paymentTerms} />
      <EntityField name="defaultTaxCodeId" label={t('fields.defaultTaxCode')} source={entities.taxCode} filter={(x) => x.scope !== 'SALES'} />
      <EntityField name="supplierGroupId" label={t('fields.group')} source={entities.partnerGroup} filter={(g) => g.appliesTo === 'SUPPLIER'} />
      <IntegerField name="leadTimeDays" label={t('fields.leadTimeDays')} min={0} />
    </ProfileSection>
  );
}

const customerSchema = z.object({
  currencyCode: zf.id(),
  customerGroupId: zf.optionalId(),
  paymentTermsId: zf.optionalId(),
  defaultTaxCodeId: zf.optionalId(),
  creditLimit: zf.optionalDecimal(),
  isOnHold: z.boolean(),
});
type CustomerValues = z.infer<typeof customerSchema>;

function CustomerProfileSection({ partner }: { partner: Partner }) {
  const profile = partner.customerProfile;
  return (
    <ProfileSection<CustomerValues>
      title={t('org.customerProfile')}
      createLabel={t('org.makeCustomer')}
      exists={!!partner.isCustomer}
      permission="partners.customer.manage"
      details={
        <DetailList
          columns={2}
          items={[
            { label: t('common.currency'), value: profile?.currencyCode },
            { label: t('fields.paymentTerms'), value: <EntityName source={entities.paymentTerms} id={profile?.paymentTermsId} /> },
            { label: t('fields.defaultTaxCode'), value: <EntityName source={entities.taxCode} id={profile?.defaultTaxCodeId} /> },
            { label: t('fields.group'), value: <EntityName source={entities.partnerGroup} id={profile?.customerGroupId} /> },
            { label: t('fields.creditLimit'), value: <Money value={profile?.creditLimit} showCurrency={false} /> },
            { label: t('fields.onHold'), value: profile?.isOnHold ? t('common.yes') : t('common.no') },
          ]}
        />
      }
      schema={customerSchema}
      defaults={{
        currencyCode: profile?.currencyCode ?? null,
        customerGroupId: profile?.customerGroupId ?? null,
        paymentTermsId: profile?.paymentTermsId ?? null,
        defaultTaxCodeId: profile?.defaultTaxCodeId ?? null,
        creditLimit: profile?.creditLimit ?? null,
        isOnHold: !!profile?.isOnHold,
      }}
      save={(api, v) =>
        api.put('/partners/{partnerId}/customer-profile', { partnerId: partner.id! }, {
          body: { ...compact(v), isOnHold: v.isOnHold } as Schemas['CustomerProfileRequest'],
          ifMatch: profile?.version ?? 0,
        })
      }
    >
      <EntityField name="currencyCode" label={t('common.currency')} source={entities.currency} required />
      <EntityField name="paymentTermsId" label={t('fields.paymentTerms')} source={entities.paymentTerms} />
      <EntityField name="defaultTaxCodeId" label={t('fields.defaultTaxCode')} source={entities.taxCode} filter={(x) => x.scope !== 'PURCHASE'} />
      <EntityField name="customerGroupId" label={t('fields.group')} source={entities.partnerGroup} filter={(g) => g.appliesTo === 'CUSTOMER'} />
      <DecimalField name="creditLimit" label={t('fields.creditLimit')} hint={t('org.creditLimitHint')} />
      <CheckboxField name="isOnHold" label={t('fields.onHold')} />
    </ProfileSection>
  );
}
