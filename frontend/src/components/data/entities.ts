// Reference data sources. Responses carry IDs (customerId, warehouseId …), so screens resolve labels
// through these sources: small master data is loaded once per company ('all'), large data is searched
// with `q` and fetched by ID ('search'); IDs shown together are fetched together (`batch`).
import { api, type CompanyApi, type Query, type Schemas } from '@/api/client';
import type { Filters, Page } from '@/api/list';
import { serverText } from '@/i18n';
import { formatDecimal } from '@/lib/format';

export interface EntitySource<T = unknown> {
  key: string;
  mode: 'all' | 'search';
  /** The permission needed to read the source; without it, IDs are shown shortened. */
  permission?: string;
  /** Company-independent reference data (currencies, countries, UoMs). */
  global?: boolean;
  // Method signatures (bivariant), so a source of a concrete type is usable where any source is.
  list(api: CompanyApi, query: Query, signal?: AbortSignal): Promise<Page<T>>;
  get?(api: CompanyApi, id: string, signal?: AbortSignal): Promise<T>;
  /** Several records by ID in one request (`filter[id][in]`, at most 100); preferred over `get` for display. */
  batch?(api: CompanyApi, ids: string[], signal?: AbortSignal): Promise<T[]>;
  id(item: T): string;
  label(item: T): string;
  description?(item: T): string | undefined;
  /** Whether the record can be picked for new documents (inactive records stay displayable). */
  selectable?(item: T): boolean;
  defaultFilters?: Filters;
}

const codeName = (item: { code?: string; name?: string }) =>
  [item.code, item.name].filter(Boolean).join(' — ');
const isActive = (item: { isActive?: boolean }) => item.isActive !== false;

function source<T>(definition: EntitySource<T>): EntitySource<T> {
  return definition;
}

const asPage = <T>(list: { data?: T[] }): Page<T> => ({ data: list.data ?? [], page: { hasMore: false } });

/** The list query that returns exactly these records (the list endpoints' `id` filter). */
export const byIds = (ids: string[]): Query => ({ 'filter[id][in]': ids, limit: ids.length });

const partnersById = async (c: CompanyApi, ids: string[], signal?: AbortSignal) =>
  (await c.get('/partners', null, { query: byIds(ids), signal })).data ?? [];

export const entities = {
  branch: source<Schemas['BranchResponse']>({
    key: 'branches',
    mode: 'all',
    permission: 'org.branch.read',
    list: (c, query, signal) => c.get('/branches', null, { query, signal }),
    id: (b) => b.id!,
    label: codeName,
    selectable: isActive,
  }),
  department: source<Schemas['DepartmentResponse']>({
    key: 'departments',
    mode: 'all',
    permission: 'org.department.read',
    list: (c, query, signal) => c.get('/departments', null, { query, signal }),
    id: (d) => d.id!,
    label: codeName,
    selectable: isActive,
  }),
  warehouse: source<Schemas['Warehouse']>({
    key: 'warehouses',
    mode: 'all',
    permission: 'inventory.warehouse.read',
    list: (c, query, signal) => c.get('/warehouses', null, { query, signal }),
    id: (w) => w.id!,
    label: codeName,
    selectable: isActive,
  }),
  taxCode: source<Schemas['TaxCodeResponse']>({
    key: 'tax-codes',
    mode: 'all',
    permission: 'org.tax_code.read',
    list: (c, query, signal) => c.get('/tax-codes', null, { query, signal }),
    id: (t) => t.id!,
    label: (t) => `${t.code} (${formatDecimal(t.ratePercent ?? '0')} %)`,
    description: (t) => t.name,
    selectable: isActive,
  }),
  paymentTerms: source<Schemas['TermsResponse']>({
    key: 'payment-terms',
    mode: 'all',
    permission: 'org.payment_terms.read',
    list: (c, query, signal) => c.get('/payment-terms', null, { query, signal }),
    id: (t) => t.id!,
    label: codeName,
    selectable: isActive,
  }),
  currency: source<Schemas['CurrencyResponse']>({
    key: 'currencies',
    mode: 'all',
    global: true,
    list: (_, query, signal) => api.get('/api/v1/reference/currencies', {}, { query, signal }),
    id: (c) => c.code!,
    label: (c) => c.code!,
    description: (c) => c.name,
    selectable: (c) => c.isActive !== false,
  }),
  country: source<Schemas['CountryResponse']>({
    key: 'countries',
    mode: 'all',
    global: true,
    list: (_, query, signal) => api.get('/api/v1/reference/countries', {}, { query, signal }),
    id: (c) => c.code!,
    label: (c) => `${c.code} — ${c.name}`,
  }),
  uom: source<Schemas['Uom']>({
    key: 'uoms',
    mode: 'all',
    global: true,
    list: async (_, __, signal) => asPage(await api.get('/api/v1/reference/uoms', {}, { signal })),
    id: (u) => u.id!,
    label: (u) => u.code!,
    description: (u) => u.name,
    selectable: (u) => u.active !== false,
  }),
  partner: source<Schemas['Partner']>({
    key: 'partners',
    mode: 'search',
    permission: 'partners.partner.read',
    list: (c, query, signal) => c.get('/partners', null, { query, signal }),
    get: (c, partnerId, signal) => c.get('/partners/{partnerId}', { partnerId }, { signal }),
    batch: partnersById,
    id: (p) => p.id!,
    label: codeName,
    selectable: (p) => p.status === 'ACTIVE',
  }),
  customer: source<Schemas['CustomerRow'] | Schemas['Partner']>({
    key: 'customers',
    mode: 'search',
    permission: 'partners.partner.read',
    list: (c, query, signal) => c.get('/customers', null, { query, signal }),
    get: (c, partnerId, signal) => c.get('/partners/{partnerId}', { partnerId }, { signal }),
    batch: partnersById,
    id: (p) => p.id!,
    label: codeName,
    selectable: (p) => p.status === 'ACTIVE',
  }),
  supplier: source<Schemas['SupplierRow'] | Schemas['Partner']>({
    key: 'suppliers',
    mode: 'search',
    permission: 'partners.partner.read',
    list: (c, query, signal) => c.get('/suppliers', null, { query, signal }),
    get: (c, partnerId, signal) => c.get('/partners/{partnerId}', { partnerId }, { signal }),
    batch: partnersById,
    id: (p) => p.id!,
    label: codeName,
    selectable: (p) => p.status === 'ACTIVE',
  }),
  partnerGroup: source<Schemas['Group']>({
    key: 'partner-groups',
    mode: 'all',
    permission: 'partners.partner.read',
    list: (c, query, signal) => c.get('/partner-groups', null, { query, signal }),
    id: (g) => g.id!,
    label: codeName,
    description: (g) => g.appliesTo,
    selectable: isActive,
  }),
  product: source<Schemas['Product']>({
    key: 'products',
    mode: 'search',
    permission: 'inventory.product.read',
    list: (c, query, signal) => c.get('/products', null, { query, signal }),
    get: (c, productId, signal) => c.get('/products/{productId}', { productId }, { signal }),
    batch: async (c, ids, signal) => (await c.get('/products', null, { query: byIds(ids), signal })).data ?? [],
    id: (p) => p.id!,
    label: codeName,
    selectable: (p) => p.status === 'ACTIVE',
  }),
  variant: source<Schemas['Variant']>({
    key: 'variants',
    mode: 'search',
    permission: 'inventory.product.read',
    list: (c, query, signal) => c.get('/variants', null, { query, signal }),
    get: (c, variantId, signal) => c.get('/variants/{variantId}', { variantId }, { signal }),
    batch: async (c, ids, signal) => (await c.get('/variants', null, { query: byIds(ids), signal })).data ?? [],
    id: (v) => v.id!,
    label: (v) => [v.sku, v.name].filter(Boolean).join(' — '),
    description: (v) => v.barcode ?? undefined,
    selectable: (v) => v.status !== 'INACTIVE' && v.status !== 'ARCHIVED',
  }),
  category: source<Schemas['Category']>({
    key: 'product-categories',
    mode: 'all',
    permission: 'inventory.product.read',
    list: (c, query, signal) => c.get('/product-categories', null, { query, signal }),
    id: (c) => c.id!,
    label: (c) => c.path ?? codeName(c),
    selectable: isActive,
  }),
  reasonCode: source<Schemas['ReasonCode']>({
    key: 'reason-codes',
    mode: 'all',
    permission: 'inventory.adjustment.manage',
    list: (c, query, signal) => c.get('/reason-codes', null, { query, signal }),
    id: (r) => r.id!,
    label: codeName,
    description: (r) => r.appliesTo,
    selectable: isActive,
  }),
  priceList: source<Schemas['PriceList']>({
    key: 'price-lists',
    mode: 'all',
    permission: 'sales.price_list.read',
    list: (c, query, signal) => c.get('/price-lists', null, { query, signal }),
    id: (p) => p.id!,
    label: (p) => `${codeName(p)} (${p.currencyCode})`,
    selectable: isActive,
  }),
  account: source<Schemas['Account']>({
    key: 'accounts',
    mode: 'all',
    permission: 'accounting.account.read',
    list: (c, query, signal) => c.get('/accounts', null, { query, signal }),
    id: (a) => a.id!,
    label: codeName,
    description: (a) => a.accountType,
    selectable: (a) => a.status !== 'INACTIVE' && a.isPostable !== false,
  }),
  journal: source<Schemas['Journal']>({
    key: 'journals',
    mode: 'all',
    permission: 'accounting.journal.manage',
    list: (c, query, signal) => c.get('/journals', null, { query, signal }),
    id: (j) => j.id!,
    label: codeName,
    selectable: isActive,
  }),
  bankAccount: source<Schemas['AccountingResponsesBankAccount']>({
    key: 'bank-accounts',
    mode: 'all',
    permission: 'accounting.bank_account.read',
    list: (c, query, signal) => c.get('/bank-accounts', null, { query, signal }),
    id: (b) => b.id!,
    label: (b) => `${codeName(b)} (${b.currencyCode})`,
    selectable: isActive,
  }),
  employee: source<Schemas['EmployeeResponse']>({
    key: 'employees',
    mode: 'search',
    permission: 'hr.employee.read',
    list: (c, query, signal) => c.get('/employees', null, { query, signal }),
    get: (c, employeeId, signal) => c.get('/employees/{employeeId}', { employeeId }, { signal }),
    batch: async (c, ids, signal) => (await c.get('/employees', null, { query: byIds(ids), signal })).data ?? [],
    id: (e) => e.id!,
    label: (e) => `${e.employeeNumber} — ${e.preferredName || e.firstName} ${e.lastName}`,
    description: (e) => e.workEmail ?? undefined,
    selectable: (e) => e.status !== 'TERMINATED',
  }),
  position: source<Schemas['PositionResponse']>({
    key: 'positions',
    mode: 'all',
    permission: 'hr.employee.read',
    list: (c, query, signal) => c.get('/positions', null, { query, signal }),
    id: (p) => p.id!,
    label: (p) => `${p.code} — ${p.title}`,
    selectable: isActive,
  }),
  leaveType: source<Schemas['LeaveType']>({
    key: 'leave-types',
    mode: 'all',
    permission: 'hr.leave.read',
    list: (c, query, signal) => c.get('/leave-types', null, { query, signal }),
    id: (l) => l.id!,
    label: codeName,
    selectable: (l) => l.active !== false,
  }),
  payComponent: source<Schemas['Component']>({
    key: 'pay-components',
    mode: 'all',
    permission: 'payroll.configuration.manage',
    list: (c, query, signal) => c.get('/pay-components', null, { query, signal }),
    id: (p) => p.id!,
    label: codeName,
    description: (p) => p.kind,
    selectable: (p) => p.active !== false,
  }),
  salaryStructure: source<Schemas['Structure']>({
    key: 'salary-structures',
    mode: 'all',
    permission: 'payroll.configuration.manage',
    list: (c, query, signal) => c.get('/salary-structures', null, { query, signal }),
    id: (s) => s.id!,
    label: codeName,
    selectable: (s) => s.active !== false,
  }),
  paySchedule: source<Schemas['Schedule']>({
    key: 'pay-schedules',
    mode: 'all',
    permission: 'payroll.configuration.manage',
    list: (c, query, signal) => c.get('/pay-schedules', null, { query, signal }),
    id: (s) => s.id!,
    label: codeName,
    selectable: (s) => s.active !== false,
  }),
  payrollPeriod: source<Schemas['PayrollPeriod']>({
    key: 'payroll-periods',
    mode: 'all',
    permission: 'payroll.run.read',
    list: (c, query, signal) => c.get('/payroll-periods', null, { query, signal }),
    id: (p) => p.id!,
    label: (p) => `${p.startDate} – ${p.endDate}`,
    description: (p) => p.status,
    selectable: (p) => p.status !== 'CLOSED',
  }),
  accountingPeriod: source<Schemas['AccountingPeriod']>({
    key: 'periods',
    mode: 'all',
    permission: 'accounting.period.read',
    list: (c, query, signal) => c.get('/periods', null, { query, signal }),
    id: (p) => p.id!,
    label: (p) => (p as { code?: string }).code ?? `${(p as { startDate?: string }).startDate}`,
  }),
  companyRole: source<Schemas['RoleResponse']>({
    key: 'roles',
    mode: 'all',
    permission: 'auth.role_assignment.manage',
    list: async (c, _, signal) => asPage(await c.get('/roles', null, { signal })),
    id: (r) => r.id!,
    label: (r) => serverText(r.name) || r.code!,
    description: (r) => r.code,
  }),
};

function documentSource<T extends { id?: string; number?: string; status?: string }>(
  key: string,
  permission: string,
  list: EntitySource<T>['list'],
  get: NonNullable<EntitySource<T>['get']>,
  describe?: (item: T) => string | undefined,
): EntitySource<T> {
  return {
    key,
    mode: 'search',
    permission,
    list,
    get,
    id: (d) => d.id!,
    label: (d) => d.number ?? '…',
    description: describe ?? ((d) => d.status),
  };
}

/** Business documents, for pickers that start one document from another. */
export const documents = {
  purchaseOrder: documentSource<Schemas['PurchaseOrder']>(
    'purchase-orders',
    'procurement.purchase_order.read',
    (c, query, signal) => c.get('/purchase-orders', null, { query, signal }),
    (c, orderId, signal) => c.get('/purchase-orders/{orderId}', { orderId }, { signal }),
  ),
  goodsReceipt: documentSource<Schemas['GoodsReceipt']>(
    'goods-receipts',
    'procurement.receipt.read',
    (c, query, signal) => c.get('/goods-receipts', null, { query, signal }),
    (c, receiptId, signal) => c.get('/goods-receipts/{receiptId}', { receiptId }, { signal }),
  ),
  supplierBill: documentSource<Schemas['SupplierBill']>(
    'supplier-bills',
    'procurement.supplier_bill.read',
    (c, query, signal) => c.get('/supplier-bills', null, { query, signal }),
    (c, billId, signal) => c.get('/supplier-bills/{billId}', { billId }, { signal }),
    (b) => b.supplierInvoiceNumber,
  ),
  salesOrder: documentSource<Schemas['SalesOrder']>(
    'sales-orders',
    'sales.order.read',
    (c, query, signal) => c.get('/sales-orders', null, { query, signal }),
    (c, orderId, signal) => c.get('/sales-orders/{orderId}', { orderId }, { signal }),
  ),
  delivery: documentSource<Schemas['Delivery']>(
    'deliveries',
    'sales.delivery.read',
    (c, query, signal) => c.get('/deliveries', null, { query, signal }),
    (c, deliveryId, signal) => c.get('/deliveries/{deliveryId}', { deliveryId }, { signal }),
  ),
  invoice: documentSource<Schemas['Invoice']>(
    'invoices',
    'sales.invoice.read',
    (c, query, signal) => c.get('/invoices', null, { query, signal }),
    (c, invoiceId, signal) => c.get('/invoices/{invoiceId}', { invoiceId }, { signal }),
  ),
};

/** Locations of one warehouse (pickers on receipts, deliveries, transfers and counts). */
export function locationSource(warehouseId: string | null | undefined): EntitySource<Schemas['Location']> {
  return {
    key: `locations:${warehouseId ?? 'none'}`,
    mode: 'all',
    permission: 'inventory.warehouse.read',
    list: (c, query, signal) =>
      warehouseId
        ? c.get('/warehouses/{warehouseId}/locations', { warehouseId }, { query, signal })
        : Promise.resolve({ data: [], page: { hasMore: false } }),
    get: (c, locationId, signal) => c.get('/locations/{locationId}', { locationId }, { signal }),
    id: (l) => l.id!,
    label: codeName,
    description: (l) => l.locationType,
    selectable: isActive,
  };
}

/** Any location by ID, for display (ledger rows, document lines). */
export const locationById: EntitySource<Schemas['Location']> = {
  key: 'location',
  mode: 'search',
  permission: 'inventory.warehouse.read',
  list: () => Promise.resolve({ data: [], page: { hasMore: false } }),
  get: (c, locationId, signal) => c.get('/locations/{locationId}', { locationId }, { signal }),
  id: (l) => l.id!,
  label: codeName,
};

export type EntityKind = keyof typeof entities;
