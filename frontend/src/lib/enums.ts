// Enumerations of the API (UPPER_SNAKE_CASE, API.md §11), for selects and filters. Labels come from
// the message catalog; unknown values from newer servers still display (enumLabel humanizes them).
import { enumLabel } from '@/i18n';

export const enums = {
  roundingMode: ['HALF_UP', 'HALF_EVEN'],
  taxRounding: ['PER_LINE', 'PER_DOCUMENT'],
  taxScope: ['SALES', 'PURCHASE', 'BOTH'],
  dueBasis: ['DOCUMENT_DATE', 'END_OF_MONTH'],
  partnerType: ['ORGANIZATION', 'INDIVIDUAL'],
  partnerStatus: ['ACTIVE', 'INACTIVE', 'BLOCKED'],
  addressType: ['BILLING', 'SHIPPING', 'OTHER'],
  groupAppliesTo: ['CUSTOMER', 'SUPPLIER'],
  productType: ['STOCKABLE', 'CONSUMABLE', 'SERVICE'],
  productStatus: ['ACTIVE', 'ARCHIVED'],
  reasonAppliesTo: ['ADJUSTMENT', 'SCRAP', 'COUNT'],
  locationType: ['INTERNAL', 'RECEIVING', 'SHIPPING', 'QUARANTINE', 'TRANSIT'],
  movementType: [
    'OPENING', 'PURCHASE_RECEIPT', 'PURCHASE_RETURN', 'SALES_ISSUE', 'SALES_RETURN', 'TRANSFER', 'TRANSFER_SHIP',
    'TRANSFER_RECEIVE', 'ADJUSTMENT', 'SCRAP', 'COUNT_ADJUSTMENT', 'REVERSAL',
  ],
  /** Movement types created directly in Inventory; receipts and issues come from Procurement and Sales. */
  manualMovementType: ['OPENING', 'TRANSFER', 'TRANSFER_SHIP', 'ADJUSTMENT', 'SCRAP'],
  documentStatus: ['DRAFT', 'POSTED', 'CANCELLED'],
  countStatus: ['DRAFT', 'IN_PROGRESS', 'COMPLETED', 'POSTED', 'CANCELLED'],
  requisitionStatus: ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PARTIALLY_ORDERED', 'ORDERED', 'CANCELLED'],
  purchaseOrderStatus: ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'],
  billingStatus: ['NOT_BILLED', 'PARTIALLY_BILLED', 'BILLED'],
  billDocumentType: ['BILL', 'DEBIT_NOTE'],
  matchStatus: ['NOT_CHECKED', 'MATCHED', 'EXCEPTION', 'OVERRIDDEN'],
  quotationStatus: ['DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED'],
  salesOrderStatus: ['DRAFT', 'CONFIRMED', 'PARTIALLY_DELIVERED', 'DELIVERED', 'CLOSED', 'CANCELLED'],
  invoiceStatus: ['NOT_INVOICED', 'PARTIALLY_INVOICED', 'INVOICED'],
  invoiceDocumentType: ['INVOICE', 'CREDIT_NOTE'],
  salesReturnStatus: ['DRAFT', 'RECEIVED', 'CANCELLED'],
  invoicePolicy: ['ORDERED', 'DELIVERED'],
  creditCheckMode: ['NONE', 'WARN', 'BLOCK'],
  accountType: ['ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'],
  periodStatus: ['OPEN', 'SOFT_CLOSED', 'CLOSED'],
  fiscalYearStatus: ['OPEN', 'CLOSED'],
  journalType: ['GENERAL', 'SALES', 'PURCHASE', 'CASH', 'BANK', 'INVENTORY', 'PAYROLL', 'CLOSING', 'OPENING'],
  mappingKey: [
    'AR_CONTROL', 'AP_CONTROL', 'INVENTORY_ASSET', 'GRNI', 'COGS', 'SALES_REVENUE', 'SALES_RETURNS', 'PURCHASE_EXPENSE',
    'PURCHASE_PRICE_VARIANCE', 'INVENTORY_ADJUSTMENT', 'INVENTORY_OPENING', 'TAX_OUTPUT', 'TAX_INPUT', 'FX_REALIZED_GAIN',
    'FX_REALIZED_LOSS', 'ROUNDING_DIFFERENCE', 'CUSTOMER_ADVANCE', 'SUPPLIER_ADVANCE', 'SALARY_EXPENSE',
    'PAYROLL_DEDUCTION_LIABILITY', 'EMPLOYER_CONTRIBUTION_EXPENSE', 'EMPLOYER_CONTRIBUTION_LIABILITY', 'SALARIES_PAYABLE',
  ],
  mappingScope: ['DEFAULT', 'PRODUCT_CATEGORY', 'WAREHOUSE', 'PARTNER_GROUP', 'TAX_CODE', 'PAY_COMPONENT', 'DEPARTMENT', 'REASON_CODE'],
  openItemKind: ['RECEIVABLE', 'PAYABLE'],
  journalEntryStatus: ['DRAFT', 'POSTED'],
  journalEntryType: ['MANUAL', 'SYSTEM', 'REVERSAL', 'OPENING', 'CLOSING', 'ADJUSTMENT'],
  openItemStatus: ['OPEN', 'PARTIALLY_SETTLED', 'SETTLED', 'VOIDED'],
  paymentDirection: ['INBOUND', 'OUTBOUND'],
  paymentMethod: ['BANK_TRANSFER', 'CASH', 'CHEQUE', 'CARD', 'OTHER'],
  paymentStatus: ['DRAFT', 'POSTED', 'VOIDED'],
  expenseStatus: ['DRAFT', 'POSTED', 'REVERSED'],
  employeeStatus: ['ONBOARDING', 'ACTIVE', 'ON_LEAVE', 'TERMINATED'],
  employmentType: ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN', 'TEMPORARY'],
  leaveStatus: ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED'],
  accrualMethod: ['ANNUAL', 'MONTHLY'],
  attendanceStatus: ['PRESENT', 'ABSENT', 'HALF_DAY', 'REMOTE', 'ON_LEAVE', 'HOLIDAY'],
  leaveLedgerType: ['ACCRUAL', 'TAKEN', 'ADJUSTMENT', 'CARRY_FORWARD', 'EXPIRY'],
  componentKind: ['EARNING', 'DEDUCTION', 'EMPLOYER_CONTRIBUTION'],
  componentCalculation: ['FIXED', 'PERCENT_OF_BASE', 'PERCENT_OF_GROSS', 'INPUT', 'STATUTORY'],
  payFrequency: ['MONTHLY', 'SEMI_MONTHLY', 'BIWEEKLY', 'WEEKLY'],
  payrollPeriodStatus: ['OPEN', 'PROCESSED', 'CLOSED'],
  payrollRunStatus: ['DRAFT', 'CALCULATING', 'CALCULATED', 'APPROVED', 'POSTED', 'PAID', 'CANCELLED'],
  runType: ['REGULAR', 'OFF_CYCLE', 'FINAL_SETTLEMENT'],
  prorationBasis: ['CALENDAR_DAYS', 'WORKING_DAYS'],
  exportFormat: ['CSV', 'XLSX', 'PDF'],
  exportStatus: ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED'],
} as const;

/** Account subtypes per account type (accounts check constraint). */
export const accountSubtypes: Record<string, readonly string[]> = {
  ASSET: ['CASH', 'BANK', 'RECEIVABLE', 'INVENTORY', 'PREPAYMENT', 'FIXED_ASSET', 'ACCUMULATED_DEPRECIATION', 'TAX_RECEIVABLE', 'OTHER_CURRENT_ASSET', 'OTHER_ASSET'],
  LIABILITY: ['PAYABLE', 'GRNI', 'TAX_PAYABLE', 'PAYROLL_LIABILITY', 'ACCRUED_LIABILITY', 'CUSTOMER_ADVANCE', 'OTHER_CURRENT_LIABILITY', 'LONG_TERM_LIABILITY'],
  EQUITY: ['EQUITY', 'RETAINED_EARNINGS', 'OPENING_BALANCE_EQUITY'],
  REVENUE: ['OPERATING_REVENUE', 'OTHER_INCOME'],
  EXPENSE: ['COST_OF_GOODS_SOLD', 'OPERATING_EXPENSE', 'PAYROLL_EXPENSE', 'DEPRECIATION', 'FX_GAIN_LOSS', 'OTHER_EXPENSE'],
};

export function enumOptions(values: readonly string[]): { value: string; label: string }[] {
  return values.map((value) => ({ value, label: enumLabel(value) }));
}
