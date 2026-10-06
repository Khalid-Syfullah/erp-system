// Role-aware navigation: an entry shows when the user holds any of its permissions in the active
// company (UX only; the server checks every request). Paths are relative to /c/$companyId.
import {
  BarChart3,
  Boxes,
  Building2,
  Calculator,
  LayoutDashboard,
  type LucideIcon,
  Settings2,
  ShoppingCart,
  Truck,
  UserRound,
  Users,
  Wallet,
} from 'lucide-react';
import type { MessageKey } from '@/i18n';

export interface NavItem {
  label: MessageKey;
  /** Path below /c/$companyId (company items) or absolute (global items). */
  path: string;
  anyOf?: string[];
  /** Only for users linked to an employee record (self-service). */
  selfService?: boolean;
  keywords?: string;
}

export interface NavGroup {
  id: string;
  label: MessageKey;
  icon: LucideIcon;
  items: NavItem[];
  /** Global administration (system administrators). */
  global?: boolean;
}

export const navGroups: NavGroup[] = [
  {
    id: 'dashboard',
    label: 'nav.dashboard',
    icon: LayoutDashboard,
    items: [{ label: 'nav.dashboard', path: '' }],
  },
  {
    id: 'organization',
    label: 'nav.organization',
    icon: Building2,
    items: [
      { label: 'nav.company', path: '/org/company', anyOf: ['org.company.manage'] },
      { label: 'nav.branches', path: '/org/branches', anyOf: ['org.branch.read'] },
      { label: 'nav.departments', path: '/org/departments', anyOf: ['org.department.read'] },
      { label: 'nav.partners', path: '/org/partners', anyOf: ['partners.partner.read'], keywords: 'customers suppliers' },
      { label: 'nav.partnerGroups', path: '/org/partner-groups', anyOf: ['partners.partner.read'] },
      { label: 'nav.taxCodes', path: '/org/tax-codes', anyOf: ['org.tax_code.read'] },
      { label: 'nav.paymentTerms', path: '/org/payment-terms', anyOf: ['org.payment_terms.read'] },
      { label: 'nav.exchangeRates', path: '/org/exchange-rates', anyOf: ['org.exchange_rate.read'] },
      { label: 'nav.numbering', path: '/org/numbering', anyOf: ['org.company.manage'] },
    ],
  },
  {
    id: 'hr',
    label: 'nav.hr',
    icon: Users,
    items: [
      { label: 'nav.employees', path: '/hr/employees', anyOf: ['hr.employee.read'] },
      { label: 'nav.orgChart', path: '/hr/organization', anyOf: ['hr.employee.read'], keywords: 'headcount' },
      { label: 'nav.positions', path: '/hr/positions', anyOf: ['hr.employee.read'] },
      { label: 'nav.departmentHeads', path: '/hr/department-heads', anyOf: ['hr.employee.read'] },
      { label: 'nav.leaveRequests', path: '/hr/leave-requests', anyOf: ['hr.leave.read'] },
      { label: 'nav.leaveBalances', path: '/hr/leave-balances', anyOf: ['hr.leave.read'] },
      { label: 'nav.leaveTypes', path: '/hr/leave-types', anyOf: ['hr.leave.read'] },
      { label: 'nav.holidays', path: '/hr/holidays', anyOf: ['hr.leave.read'] },
      { label: 'nav.attendance', path: '/hr/attendance', anyOf: ['hr.attendance.read'] },
      { label: 'nav.hrSettings', path: '/hr/settings', anyOf: ['hr.leave.configure'] },
    ],
  },
  {
    id: 'self-service',
    label: 'nav.selfService',
    icon: UserRound,
    items: [
      { label: 'nav.myProfile', path: '/me/profile', selfService: true },
      { label: 'nav.myLeave', path: '/me/leave', selfService: true },
      { label: 'nav.myAttendance', path: '/me/attendance', selfService: true },
      { label: 'nav.myPayslips', path: '/me/payslips', selfService: true },
      { label: 'nav.myTeam', path: '/me/team', selfService: true },
    ],
  },
  {
    id: 'inventory',
    label: 'nav.inventory',
    icon: Boxes,
    items: [
      { label: 'nav.products', path: '/inventory/products', anyOf: ['inventory.product.read'], keywords: 'variants sku' },
      { label: 'nav.categories', path: '/inventory/categories', anyOf: ['inventory.product.read'] },
      { label: 'nav.attributes', path: '/inventory/attributes', anyOf: ['inventory.product.read'] },
      { label: 'nav.warehouses', path: '/inventory/warehouses', anyOf: ['inventory.warehouse.read'], keywords: 'locations' },
      { label: 'nav.stockLevels', path: '/inventory/stock', anyOf: ['inventory.stock.read'] },
      { label: 'nav.stockLedger', path: '/inventory/ledger', anyOf: ['inventory.stock.read'] },
      { label: 'nav.valuation', path: '/inventory/valuation', anyOf: ['inventory.valuation.read'] },
      { label: 'nav.movements', path: '/inventory/movements', anyOf: ['inventory.movement.read'], keywords: 'transfer adjustment' },
      { label: 'nav.counts', path: '/inventory/counts', anyOf: ['inventory.count.manage'] },
      { label: 'nav.reasonCodes', path: '/inventory/reason-codes', anyOf: ['inventory.adjustment.manage'] },
      { label: 'nav.inventorySettings', path: '/inventory/settings', anyOf: ['inventory.settings.manage'] },
    ],
  },
  {
    id: 'procurement',
    label: 'nav.procurement',
    icon: Truck,
    items: [
      { label: 'nav.suppliers', path: '/procurement/suppliers', anyOf: ['partners.partner.read'] },
      { label: 'nav.requisitions', path: '/procurement/requisitions', anyOf: ['procurement.requisition.read'] },
      { label: 'nav.purchaseOrders', path: '/procurement/orders', anyOf: ['procurement.purchase_order.read'], keywords: 'po' },
      { label: 'nav.goodsReceipts', path: '/procurement/receipts', anyOf: ['procurement.receipt.read'], keywords: 'grn' },
      { label: 'nav.purchaseReturns', path: '/procurement/returns', anyOf: ['procurement.return.manage'] },
      { label: 'nav.supplierBills', path: '/procurement/bills', anyOf: ['procurement.supplier_bill.read'], keywords: 'ap invoice' },
      { label: 'nav.procurementSettings', path: '/procurement/settings', anyOf: ['procurement.settings.manage'] },
    ],
  },
  {
    id: 'sales',
    label: 'nav.sales',
    icon: ShoppingCart,
    items: [
      { label: 'nav.customers', path: '/sales/customers', anyOf: ['partners.partner.read'] },
      { label: 'nav.priceLists', path: '/sales/price-lists', anyOf: ['sales.price_list.read'] },
      { label: 'nav.quotations', path: '/sales/quotations', anyOf: ['sales.quotation.read'] },
      { label: 'nav.salesOrders', path: '/sales/orders', anyOf: ['sales.order.read'] },
      { label: 'nav.deliveries', path: '/sales/deliveries', anyOf: ['sales.delivery.read'] },
      { label: 'nav.salesReturns', path: '/sales/returns', anyOf: ['sales.return.manage'] },
      { label: 'nav.invoices', path: '/sales/invoices', anyOf: ['sales.invoice.read'], keywords: 'credit note' },
      { label: 'nav.salesSettings', path: '/sales/settings', anyOf: ['sales.settings.manage'] },
    ],
  },
  {
    id: 'accounting',
    label: 'nav.accounting',
    icon: Calculator,
    items: [
      { label: 'nav.accounts', path: '/accounting/accounts', anyOf: ['accounting.account.read'], keywords: 'coa' },
      { label: 'nav.journalEntries', path: '/accounting/entries', anyOf: ['accounting.journal_entry.read'] },
      { label: 'nav.payments', path: '/accounting/payments', anyOf: ['accounting.payment.read'], keywords: 'allocation receipt' },
      { label: 'nav.receivables', path: '/accounting/receivables', anyOf: ['accounting.ar.read'], keywords: 'ar' },
      { label: 'nav.payables', path: '/accounting/payables', anyOf: ['accounting.ap.read'], keywords: 'ap' },
      { label: 'nav.expenses', path: '/accounting/expenses', anyOf: ['accounting.expense.read'] },
      { label: 'nav.bankAccounts', path: '/accounting/bank-accounts', anyOf: ['accounting.bank_account.read'], keywords: 'reconciliation' },
      { label: 'nav.periods', path: '/accounting/periods', anyOf: ['accounting.period.read'], keywords: 'fiscal year close' },
      { label: 'nav.journals', path: '/accounting/journals', anyOf: ['accounting.journal.manage'] },
      { label: 'nav.accountMappings', path: '/accounting/mappings', anyOf: ['accounting.account_mapping.manage'] },
      { label: 'nav.accountingSettings', path: '/accounting/settings', anyOf: ['accounting.settings.manage'] },
    ],
  },
  {
    id: 'payroll',
    label: 'nav.payroll',
    icon: Wallet,
    items: [
      { label: 'nav.payrollRuns', path: '/payroll/runs', anyOf: ['payroll.run.read'] },
      { label: 'nav.payrollPeriods', path: '/payroll/periods', anyOf: ['payroll.run.read'], keywords: 'inputs' },
      { label: 'nav.payComponents', path: '/payroll/components', anyOf: ['payroll.configuration.manage'] },
      { label: 'nav.salaryStructures', path: '/payroll/structures', anyOf: ['payroll.configuration.manage'] },
      { label: 'nav.paySchedules', path: '/payroll/schedules', anyOf: ['payroll.configuration.manage'] },
      { label: 'nav.payrollSettings', path: '/payroll/settings', anyOf: ['payroll.configuration.manage'] },
    ],
  },
  {
    id: 'reports',
    label: 'nav.reports',
    icon: BarChart3,
    items: [
      { label: 'nav.reportCentre', path: '/reports', keywords: 'analytics statements' },
      { label: 'nav.dashboards', path: '/reports/dashboards' },
      { label: 'nav.savedReports', path: '/reports/saved' },
      { label: 'nav.exports', path: '/reports/exports', anyOf: ['reporting.export.create'] },
    ],
  },
  {
    id: 'administration',
    label: 'nav.administration',
    icon: Settings2,
    items: [
      { label: 'nav.companyUsers', path: '/admin/users', anyOf: ['auth.role_assignment.manage'], keywords: 'roles assignments' },
      { label: 'nav.auditLog', path: '/admin/audit', anyOf: ['admin.audit.read'] },
    ],
  },
];

/** System administration, outside any company. */
export const globalAdminGroup: NavGroup = {
  id: 'system',
  label: 'nav.administration',
  icon: Settings2,
  global: true,
  items: [
    { label: 'nav.systemUsers', path: '/admin/users' },
    { label: 'nav.roles', path: '/admin/roles' },
    { label: 'nav.serviceAccounts', path: '/admin/service-accounts' },
    { label: 'nav.allCompanies', path: '/admin/companies' },
    { label: 'nav.globalAudit', path: '/admin/audit' },
  ],
};

export function visibleGroups(
  canAny: (...permissions: string[]) => boolean,
  isEmployee: boolean,
): NavGroup[] {
  return navGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => (item.selfService ? isEmployee : canAny(...(item.anyOf ?? [])))),
    }))
    .filter((group) => group.items.length > 0);
}
