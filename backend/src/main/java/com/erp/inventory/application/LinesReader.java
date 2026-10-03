package com.erp.inventory.application;

import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code lines} array of a movement merge patch (API.md §4: arrays are replaced whole).
 * Strict like the request-body deserialization: decimals as strings, no unknown members.
 */
@Component
class LinesReader {

    static final Set<String> MEMBERS =
            Set.of("variantId", "fromLocationId", "toLocationId", "quantity", "uomId", "unitCostBase");
    private static final Pattern DECIMAL = Pattern.compile("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?$");

    List<InventoryCommands.Line> read(JsonNode array) {
        List<FieldViolation> violations = new ArrayList<>();
        List<InventoryCommands.Line> lines = new ArrayList<>();
        if (array == null || !array.isArray()) {
            throw ApiException.validationFailed(
                    "The lines are invalid.",
                    List.of(FieldViolation.atPointer("/lines", "INVALID_VALUE", "must be an array")));
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            String at = "/lines/" + i;
            if (!node.isObject()) {
                violations.add(FieldViolation.atPointer(at, "INVALID_VALUE", "must be an object"));
                continue;
            }
            node.propertyNames().forEach(name -> {
                if (!MEMBERS.contains(name)) {
                    violations.add(FieldViolation.atPointer(
                            at + "/" + name, "UNKNOWN_PROPERTY", "is not a recognized property"));
                }
            });
            UUID variant = uuid(node, "variantId", true, at, violations);
            UUID from = uuid(node, "fromLocationId", false, at, violations);
            UUID to = uuid(node, "toLocationId", false, at, violations);
            UUID uom = uuid(node, "uomId", true, at, violations);
            BigDecimal quantity = decimal(node, "quantity", true, at, violations);
            BigDecimal cost = decimal(node, "unitCostBase", false, at, violations);
            if (quantity != null && quantity.signum() <= 0) {
                violations.add(FieldViolation.atPointer(at + "/quantity", "POSITIVE", "must be greater than 0"));
            }
            if (variant != null && uom != null && quantity != null) {
                lines.add(new InventoryCommands.Line(variant, from, to, quantity, uom, cost, null, null, null, null));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The lines are invalid.", violations);
        }
        return lines;
    }

    private static @Nullable UUID uuid(
            JsonNode node, String name, boolean required, String at, List<FieldViolation> violations) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            if (required) {
                violations.add(FieldViolation.atPointer(at + "/" + name, "NOT_NULL", "must not be null"));
            }
            return null;
        }
        try {
            return UUID.fromString(value.asString());
        } catch (IllegalArgumentException e) {
            violations.add(FieldViolation.atPointer(at + "/" + name, "INVALID_VALUE", "must be a UUID"));
            return null;
        }
    }

    private static @Nullable BigDecimal decimal(
            JsonNode node, String name, boolean required, String at, List<FieldViolation> violations) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            if (required) {
                violations.add(FieldViolation.atPointer(at + "/" + name, "NOT_NULL", "must not be null"));
            }
            return null;
        }
        if (!value.isString() || !DECIMAL.matcher(value.stringValue()).matches()) {
            violations.add(FieldViolation.atPointer(
                    at + "/" + name, "INVALID_VALUE", "must be a decimal string with at most 6 decimal places"));
            return null;
        }
        return new BigDecimal(value.stringValue());
    }
}
