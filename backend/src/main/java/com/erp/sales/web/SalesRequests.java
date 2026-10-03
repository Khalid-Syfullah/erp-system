package com.erp.sales.web;

import com.erp.sales.application.SalesCommands;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Request bodies shared by several Sales endpoints. */
final class SalesRequests {

    private SalesRequests() {}

    /** A quotation or order line; without {@code unitPrice} the price list's price applies (SAL-1). */
    record PricedLine(
            @NotNull UUID variantId,
            @Size(max = 300) @Nullable String description,

            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,

            @NotNull UUID uomId,

            @DecimalMin("0") @Digits(integer = 13, fraction = 6) @Nullable BigDecimal unitPrice,

            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) @Nullable BigDecimal discountPercent,

            @Nullable UUID taxCodeId) {

        SalesCommands.PricedLine command() {
            return new SalesCommands.PricedLine(
                    variantId,
                    description,
                    quantity,
                    uomId,
                    unitPrice,
                    discountPercent == null ? BigDecimal.ZERO : discountPercent,
                    taxCodeId);
        }
    }

    record Reason(@NotBlank @Size(max = 500) String reason) {}

    record OptionalReason(@Size(max = 500) @Nullable String reason) {}
}
