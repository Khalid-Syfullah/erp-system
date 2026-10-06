// Seeds a demo company for local work and the end-to-end suite, through the public API only.
// Idempotent: records found by code are reused. Credentials (with TOTP secrets) are kept in
// e2e/.state/seed.json, which is git-ignored.
//
//   node --experimental-strip-types e2e/support/seed.ts
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { ApiFailure, login, mailToken, Session, type Credentials } from './erp-api.ts';

const STATE_FILE = resolve(import.meta.dirname, '../.state/seed.json');

export interface SeedState {
  admin: Credentials;
  users: Record<'alice' | 'bob' | 'erin', Credentials>;
  companyId: string;
  ids: Record<string, string>;
}

const PASSWORD = process.env.ERP_SEED_PASSWORD ?? 'Blue-Ocean-Lantern-2026';
const ADMIN: Credentials = {
  email: process.env.ERP_ADMIN_EMAIL ?? 'admin@erp.local',
  password: process.env.ERP_ADMIN_PASSWORD ?? 'Correct-Horse-Battery-77',
};

/** Roles per demo user (SECURITY.md §4.3). Alice does the work, Bob approves (segregation of duties). */
const USERS = {
  alice: {
    email: 'alice@erp.local',
    name: 'Alice Operations',
    roles: ['MASTER_DATA', 'COMPANY_ADMIN', 'BUYER', 'WAREHOUSE_CLERK', 'INVENTORY_MANAGER', 'SALES_REP', 'BILLING_CLERK', 'AP_CLERK', 'AR_CLERK', 'ACCOUNTANT', 'HR_MANAGER', 'PAYROLL_OFFICER'],
  },
  bob: {
    email: 'bob@erp.local',
    name: 'Bob Approver',
    roles: ['PROCUREMENT_MANAGER', 'SALES_MANAGER', 'FINANCIAL_CONTROLLER', 'PAYROLL_APPROVER', 'AUDITOR'],
  },
  erin: { email: 'erin@erp.local', name: 'Erin Employee', roles: ['EMPLOYEE'] },
} as const;

export function readSeedState(): SeedState {
  return JSON.parse(readFileSync(STATE_FILE, 'utf8')) as SeedState;
}

function save(state: Partial<SeedState>) {
  mkdirSync(dirname(STATE_FILE), { recursive: true });
  writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
}

const today = () => new Date().toISOString().slice(0, 10);

async function findByCode(s: Session, path: string, code: string, field = 'code'): Promise<any | undefined> {
  try {
    const page = await s.get(path, { [`filter[${field}]`]: code, limit: 1 });
    return page.data?.[0];
  } catch (error) {
    // Lists without a code filter (allowlists differ per endpoint, API.md §8.2) are scanned.
    if (!(error instanceof ApiFailure) || error.status !== 400) throw error;
    return (await s.all(path)).find((row) => row[field] === code);
  }
}

async function ensure(s: Session, path: string, code: string, body: Record<string, unknown>, field = 'code'): Promise<any> {
  return (await findByCode(s, path, code, field)) ?? (await s.post(path, body));
}

async function ensureUser(admin: Session, key: keyof typeof USERS, previous?: Credentials): Promise<Credentials> {
  const spec = USERS[key];
  const existing = (await admin.get('/api/v1/admin/users', { 'filter[email]': spec.email })).data?.[0];
  if (!existing) {
    await admin.post('/api/v1/admin/users', { email: spec.email, displayName: spec.name });
  }
  if (!existing || existing.status === 'INVITED') {
    const token = await mailToken(spec.email, '/accept-invitation');
    await new Session().post('/api/v1/auth/invitations/accept', { token, password: PASSWORD });
  }
  return previous ?? { email: spec.email, password: PASSWORD };
}

async function assignRoles(admin: Session, companyId: string, key: keyof typeof USERS) {
  const spec = USERS[key];
  const user = (await admin.get('/api/v1/admin/users', { 'filter[email]': spec.email })).data[0];
  const roles = (await admin.get('/api/v1/admin/roles')).data as { id: string; code: string }[];
  const current = (await admin.get(`/api/v1/admin/users/${user.id}/role-assignments`)).data as { roleCode: string; companyId: string }[];
  for (const code of spec.roles) {
    if (current.some((a) => a.roleCode === code && a.companyId === companyId)) continue;
    const role = roles.find((r) => r.code === code)!;
    await admin.post(`/api/v1/admin/users/${user.id}/role-assignments`, { companyId, roleId: role.id });
  }
  return user.id as string;
}

export async function seed(): Promise<SeedState> {
  const previous = existsSync(STATE_FILE) ? (JSON.parse(readFileSync(STATE_FILE, 'utf8')) as Partial<SeedState>) : {};
  const signedIn = await login(previous.admin ?? ADMIN);
  const admin = signedIn.session;
  const state: Partial<SeedState> = { ...previous, admin: signedIn.credentials, ids: previous.ids ?? {} };
  save(state);

  // A custom role for partner master data, which no system role grants (SECURITY.md §4.3).
  const roles = (await admin.get('/api/v1/admin/roles')).data as { code: string }[];
  if (!roles.some((r) => r.code === 'MASTER_DATA')) {
    await admin.post('/api/v1/admin/roles', {
      code: 'MASTER_DATA',
      name: 'Master data steward',
      description: 'Partners and their profiles',
      requiresMfa: false,
      permissions: ['partners.partner.read', 'partners.partner.manage', 'partners.customer.manage', 'partners.supplier.manage'],
    });
  }

  // Company (seeded by org.company.created with the standard chart, mappings, journals and fiscal year).
  let company = (await admin.get('/api/v1/admin/companies', { 'filter[code]': 'DEMO' })).data?.[0];
  if (!company) {
    company = await admin.post('/api/v1/companies', {
      code: 'DEMO',
      legalName: 'Demo Trading LLC',
      displayName: 'Demo Trading',
      countryCode: 'US',
      baseCurrency: 'USD',
      timezone: 'UTC',
      fiscalYearStartMonth: 1,
    });
  }
  state.companyId = company.id;

  const users = { ...(previous.users ?? {}) } as SeedState['users'];
  const userIds: Record<string, string> = {};
  const sessions: Partial<Record<keyof typeof USERS, Session>> = {};
  for (const key of ['alice', 'bob', 'erin'] as const) {
    users[key] = await ensureUser(admin, key, users[key]);
    userIds[key] = await assignRoles(admin, company.id, key);
    const signed = await login(users[key]);
    users[key] = signed.credentials;
    sessions[key] = signed.session;
    state.users = users;
    save(state);
  }

  const alice = sessions.alice!;
  alice.companyId = company.id;
  const ids = state.ids!;

  // Organization
  const branch = await ensure(alice, '/branches', 'MAIN', { code: 'MAIN', name: 'Main branch', countryCode: 'US' });
  ids.branch = branch.id;
  const terms = await ensure(alice, '/payment-terms', 'NET30', { code: 'NET30', name: 'Net 30', dueDays: 30 });
  ids.terms = terms.id;
  ids.purchaseTax = (await ensure(alice, '/tax-codes', 'VAT10', { code: 'VAT10', name: 'Input VAT 10 %', scope: 'PURCHASE', ratePercent: '10' })).id;
  ids.salesTax = (await ensure(alice, '/tax-codes', 'OUT10', { code: 'OUT10', name: 'Output VAT 10 %', scope: 'SALES', ratePercent: '10' })).id;

  // Inventory
  const uoms = (await alice.get('/api/v1/reference/uoms')).data as { id: string; code: string }[];
  ids.uomEA = uoms.find((u) => u.code === 'EA')!.id;
  const warehouse = await ensure(alice, '/warehouses', 'WH1', { code: 'WH1', name: 'Main warehouse', branchId: branch.id });
  ids.warehouse = warehouse.id;
  const locations = await alice.all(`/warehouses/${warehouse.id}/locations`);
  ids.stockLocation = (locations.find((l) => l.locationType === 'INTERNAL') ?? locations[0]).id;
  const category = await ensure(alice, '/product-categories', 'GOODS', { code: 'GOODS', name: 'Goods' });
  ids.category = category.id;
  for (const [code, name] of [
    ['WIDGET', 'Widget'],
    ['GADGET', 'Gadget'],
  ]) {
    let product = await findByCode(alice, '/products', code!);
    if (!product) {
      product = await alice.post('/products', {
        code, name, categoryId: category.id, productType: 'STOCKABLE', baseUomId: ids.uomEA,
        salesTaxCodeId: ids.salesTax, purchaseTaxCodeId: ids.purchaseTax,
      });
    }
    product = await alice.get(`/products/${product.id}`);
    ids[`product${code}`] = product.id;
    ids[`variant${code}`] = product.variants[0].id;
  }
  for (const [code, name, appliesTo] of [
    ['DAMAGE', 'Damaged', 'ADJUSTMENT'],
    ['EXPIRED', 'Expired', 'SCRAP'],
    ['CYCLE', 'Cycle count', 'COUNT'],
  ]) {
    const existing = (await alice.all('/reason-codes')).find((r) => r.code === code);
    ids[`reason${code}`] = (existing ?? (await alice.post('/reason-codes', { code, name, appliesTo }))).id;
  }
  const levels = await alice.get('/stock-levels', { 'filter[warehouseId]': warehouse.id, 'filter[variantId]': ids.variantWIDGET });
  if ((levels.data ?? []).length === 0) {
    await alice.post(
      '/stock-movements',
      {
        movementType: 'OPENING',
        movementDate: today(),
        warehouseId: warehouse.id,
        postImmediately: true,
        lines: [
          { variantId: ids.variantWIDGET, toLocationId: ids.stockLocation, quantity: '100', uomId: ids.uomEA, unitCostBase: '10' },
          { variantId: ids.variantGADGET, toLocationId: ids.stockLocation, quantity: '50', uomId: ids.uomEA, unitCostBase: '20' },
        ],
      },
      { idempotencyKey: randomUUID() },
    );
  }

  ids.openingMovement = (await alice.get('/stock-movements', { 'filter[movementType]': 'OPENING', limit: 1 })).data[0].id;

  // Partners
  for (const [code, kind, name] of [
    ['ACME', 'supplier', 'Acme Supplies'],
    ['CUST1', 'customer', 'Contoso Retail'],
  ]) {
    let partner = await findByCode(alice, '/partners', code!);
    if (!partner) partner = await alice.post('/partners', { code, name, partnerType: 'ORGANIZATION' });
    partner = await alice.get(`/partners/${partner.id}`);
    ids[`partner${code}`] = partner.id;
    if (kind === 'supplier' && !partner.supplierProfile) {
      await alice.put(`/partners/${partner.id}/supplier-profile`, { currencyCode: 'USD', paymentTermsId: terms.id, defaultTaxCodeId: ids.purchaseTax }, { ifMatch: 0 });
    }
    if (kind === 'customer' && !partner.customerProfile) {
      await alice.put(`/partners/${partner.id}/customer-profile`, { currencyCode: 'USD', paymentTermsId: terms.id, defaultTaxCodeId: ids.salesTax }, { ifMatch: 0 });
    }
  }

  // Sales price list (price lists are managed by the sales manager)
  const bob = sessions.bob!;
  bob.companyId = company.id;
  const list = await ensure(bob, '/price-lists', 'STD', { code: 'STD', name: 'Standard', currencyCode: 'USD', isDefault: true });
  ids.priceList = list.id;
  const items = await bob.get(`/price-lists/${list.id}/items`);
  for (const [code, price] of [
    ['WIDGET', '25'],
    ['GADGET', '45'],
  ]) {
    if (!(items.data ?? []).some((i: { variantId: string }) => i.variantId === ids[`variant${code}`])) {
      await bob.post(`/price-lists/${list.id}/items`, { variantId: ids[`variant${code}`], uomId: ids.uomEA, unitPrice: price });
    }
  }

  // Accounting: a USD bank account on GL 1010
  const cash = (await alice.get('/accounts', { 'filter[code]': '1010' })).data[0];
  const banks = await alice.all('/bank-accounts');
  ids.bankAccount = (banks.find((b) => b.name === 'Operating USD') ?? (await alice.post('/bank-accounts', { name: 'Operating USD', accountId: cash.id, currencyCode: 'USD', bankName: 'First Bank' }))).id;

  // HR
  ids.department = (await ensure(alice, '/departments', 'OPS', { code: 'OPS', name: 'Operations', branchId: branch.id })).id;
  ids.position = (await ensure(alice, '/positions', 'CLERK', { code: 'CLERK', title: 'Clerk', departmentId: ids.department })).id;
  ids.leaveType = (await ensure(alice, '/leave-types', 'ANNUAL', { code: 'ANNUAL', name: 'Annual leave', annualEntitlementDays: '24', accrualMethod: 'ANNUAL', isPaid: true })).id;
  const hireDate = `${new Date().getFullYear()}-01-01`;
  const employees: [string, string, string, string | null][] = [
    ['E001', 'Alice', 'Operations', null],
    ['E002', 'Bob', 'Approver', null],
    ['E003', 'Erin', 'Employee', 'erin'],
  ];
  for (const [number, firstName, lastName, link] of employees) {
    let employee = await findByCode(alice, '/employees', number, 'employeeNumber');
    if (!employee) {
      employee = await alice.post('/employees', {
        employeeNumber: number, firstName, lastName, hireDate,
        workEmail: `${firstName.toLowerCase()}.${lastName.toLowerCase()}@demo.example`,
        initialAssignment: {
          branchId: branch.id, departmentId: ids.department, positionId: ids.position,
          managerEmployeeId: number === 'E003' ? ids.employeeE001 : undefined, effectiveFrom: hireDate,
        },
      });
    }
    employee = await alice.get(`/employees/${employee.id}`);
    if (employee.status !== 'ACTIVE') employee = await alice.post(`/employees/${employee.id}/activate`, undefined, { ifMatch: employee.version });
    if (link && !employee.userId) {
      employee = await alice.put(`/employees/${employee.id}/user`, { userId: userIds[link] }, { ifMatch: employee.version });
    }
    ids[`employee${number}`] = employee.id;
  }
  await alice.post('/leave-accruals', { asOf: today() });

  // Payroll
  const components: Record<string, string> = {};
  for (const [code, kind, calculation, rate, amount, taxable, rule, sequence] of [
    ['BASIC', 'EARNING', 'PERCENT_OF_BASE', '100', null, true, null, 10],
    ['HOUSING', 'EARNING', 'FIXED', null, '500', false, null, 20],
    ['OVERTIME', 'EARNING', 'INPUT', '20', null, true, null, 30],
    ['TAX', 'DEDUCTION', 'STATUTORY', '10', null, true, 'FLAT_PERCENT', 10],
    ['PENSION', 'DEDUCTION', 'PERCENT_OF_GROSS', '5', null, true, null, 20],
    ['PENSION_ER', 'EMPLOYER_CONTRIBUTION', 'PERCENT_OF_GROSS', '8', null, true, null, 10],
  ] as const) {
    components[code] = (
      await ensure(alice, '/pay-components', code, {
        code, name: code.replace('_', ' '), kind, calculation, defaultRate: rate ?? undefined,
        defaultAmount: amount ?? undefined, isTaxable: taxable, statutoryRuleCode: rule ?? undefined, sequence,
      })
    ).id;
  }
  ids.structure = (await ensure(alice, '/salary-structures', 'STD', { code: 'STD', name: 'Standard', components: Object.values(components).map((componentId) => ({ componentId })) })).id;
  let schedule = await findByCode(alice, '/pay-schedules', 'MONTHLY');
  if (!schedule) {
    schedule = await alice.post('/pay-schedules', { code: 'MONTHLY', name: 'Monthly', frequency: 'MONTHLY', currencyCode: 'USD' });
    await alice.post(`/pay-schedules/${schedule.id}/periods`, { year: new Date().getFullYear() });
  }
  ids.schedule = schedule.id;
  for (const [number, base] of [
    ['E001', '4000'],
    ['E002', '5000'],
    ['E003', '3000'],
  ]) {
    const employeeId = ids[`employee${number}`]!;
    const existing = (await alice.get(`/employees/${employeeId}/compensations`)).data ?? [];
    if (existing.length === 0) {
      await alice.post(`/employees/${employeeId}/compensations`, { payScheduleId: schedule.id, salaryStructureId: ids.structure, baseAmount: base, effectiveFrom: hireDate });
    }
  }

  save(state);
  return state as SeedState;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  seed()
    .then((state) => console.log(`Seeded company ${state.companyId}; users: ${Object.values(state.users).map((u) => u.email).join(', ')}`))
    .catch((error: unknown) => {
      console.error(error instanceof ApiFailure ? error.message : error);
      process.exit(1);
    });
}
