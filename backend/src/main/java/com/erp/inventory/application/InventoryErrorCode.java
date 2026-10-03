package com.erp.inventory.application;

import com.erp.platform.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of the Inventory module (API.md §6.1). */
public enum InventoryErrorCode implements ErrorCode {
    INSUFFICIENT_STOCK(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient stock"),
    RESERVED_STOCK_CONFLICT(HttpStatus.UNPROCESSABLE_CONTENT, "Reserved stock conflict"),
    UOM_NOT_CONVERTIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "Unit not convertible"),
    REVERSAL_NOT_POSSIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "Reversal not possible"),
    ADJUSTMENT_APPROVAL_REQUIRED(HttpStatus.FORBIDDEN, "Adjustment approval required"),
    DUPLICATE_SKU(HttpStatus.CONFLICT, "Duplicate SKU"),
    DUPLICATE_BARCODE(HttpStatus.CONFLICT, "Duplicate barcode"),
    DUPLICATE_VARIANT(HttpStatus.CONFLICT, "Duplicate variant"),
    DUPLICATE_SOURCE_DOCUMENT(HttpStatus.CONFLICT, "Source document already processed");

    private final HttpStatus status;
    private final String title;

    InventoryErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
