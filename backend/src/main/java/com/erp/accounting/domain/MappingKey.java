package com.erp.accounting.domain;

import java.util.Set;

/**
 * Account determination keys (DATABASE.md §5.8, PRODUCT_SPEC.md §8.6) and the scopes a mapping of
 * the key may be defined for besides DEFAULT.
 */
public enum MappingKey {
    AR_CONTROL(ScopeType.PARTNER_GROUP),
    AP_CONTROL(ScopeType.PARTNER_GROUP),
    INVENTORY_ASSET(ScopeType.PRODUCT_CATEGORY, ScopeType.WAREHOUSE),
    GRNI(),
    COGS(ScopeType.PRODUCT_CATEGORY),
    SALES_REVENUE(ScopeType.PRODUCT_CATEGORY),
    SALES_RETURNS(ScopeType.PRODUCT_CATEGORY),
    PURCHASE_EXPENSE(ScopeType.PRODUCT_CATEGORY),
    PURCHASE_PRICE_VARIANCE(ScopeType.PRODUCT_CATEGORY),
    INVENTORY_ADJUSTMENT(ScopeType.REASON_CODE),
    INVENTORY_OPENING(),
    TAX_OUTPUT(ScopeType.TAX_CODE),
    TAX_INPUT(ScopeType.TAX_CODE),
    FX_REALIZED_GAIN(),
    FX_REALIZED_LOSS(),
    ROUNDING_DIFFERENCE(),
    CUSTOMER_ADVANCE(ScopeType.PARTNER_GROUP),
    SUPPLIER_ADVANCE(ScopeType.PARTNER_GROUP),
    SALARY_EXPENSE(ScopeType.PAY_COMPONENT, ScopeType.DEPARTMENT),
    PAYROLL_DEDUCTION_LIABILITY(ScopeType.PAY_COMPONENT),
    EMPLOYER_CONTRIBUTION_EXPENSE(ScopeType.PAY_COMPONENT, ScopeType.DEPARTMENT),
    EMPLOYER_CONTRIBUTION_LIABILITY(ScopeType.PAY_COMPONENT),
    SALARIES_PAYABLE();

    private final Set<ScopeType> scopes;

    MappingKey(ScopeType... scopes) {
        this.scopes = Set.of(scopes);
    }

    public boolean allows(ScopeType scope) {
        return scope == ScopeType.DEFAULT || scopes.contains(scope);
    }

    /**
     * Whether an account of the type and subtype can serve the key: control keys need their control
     * subtype (AR_CONTROL a RECEIVABLE account, …), the others an account of the right type.
     */
    public boolean accepts(AccountType type, String subtype) {
        return switch (this) {
            case AR_CONTROL -> "RECEIVABLE".equals(subtype);
            case AP_CONTROL -> "PAYABLE".equals(subtype);
            case INVENTORY_ASSET -> "INVENTORY".equals(subtype);
            case GRNI -> "GRNI".equals(subtype);
            case TAX_OUTPUT -> "TAX_PAYABLE".equals(subtype);
            case TAX_INPUT -> "TAX_RECEIVABLE".equals(subtype);
            case SALES_REVENUE, SALES_RETURNS -> type == AccountType.REVENUE;
            case COGS,
                    PURCHASE_EXPENSE,
                    PURCHASE_PRICE_VARIANCE,
                    INVENTORY_ADJUSTMENT,
                    SALARY_EXPENSE,
                    EMPLOYER_CONTRIBUTION_EXPENSE -> type == AccountType.EXPENSE;
            case INVENTORY_OPENING -> type == AccountType.EQUITY;
            case FX_REALIZED_GAIN, FX_REALIZED_LOSS, ROUNDING_DIFFERENCE ->
                type == AccountType.EXPENSE || type == AccountType.REVENUE;
            case CUSTOMER_ADVANCE, PAYROLL_DEDUCTION_LIABILITY, EMPLOYER_CONTRIBUTION_LIABILITY, SALARIES_PAYABLE ->
                type == AccountType.LIABILITY;
            case SUPPLIER_ADVANCE -> type == AccountType.ASSET;
        };
    }

    /** The partner group kind a PARTNER_GROUP-scoped mapping of the key applies to. */
    public String groupKind() {
        return this == AP_CONTROL || this == SUPPLIER_ADVANCE ? "SUPPLIER" : "CUSTOMER";
    }

    /** What a mapping is scoped to. */
    public enum ScopeType {
        DEFAULT,
        PRODUCT_CATEGORY,
        WAREHOUSE,
        PARTNER_GROUP,
        TAX_CODE,
        PAY_COMPONENT,
        DEPARTMENT,
        REASON_CODE
    }
}
