package com.erp.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MergePatchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> MEMBERS = Set.of("ref", "day", "amount");

    private static MergePatch patch(String json) {
        JsonNode node = JSON.readTree(json);
        return MergePatch.of(node, MEMBERS);
    }

    @Test
    void referencesDatesAndDecimalsDistinguishAbsentNullAndValues() {
        UUID id = UUID.randomUUID();
        MergePatch patch = patch("{\"ref\":\"" + id + "\",\"day\":null,\"amount\":\"12.50\"}");

        assertThat(patch.uuid("ref", false).value()).isEqualTo(id);
        assertThat(patch.date("day", false)).isEqualTo(new MergePatch.Member<LocalDate>(true, null));
        assertThat(patch.decimal("amount", BigDecimal.ZERO, BigDecimal.TEN.pow(3), 2)
                        .value())
                .isEqualByComparingTo("12.5");
        patch.throwIfInvalid();
        assertThat(patch("{}").uuid("ref", false).present()).isFalse();
    }

    @Test
    void invalidValuesAreCollected() {
        MergePatch patch = patch("{\"ref\":\"nope\",\"day\":\"2026-13-01\",\"amount\":12.5}");
        patch.uuid("ref", false);
        patch.date("day", true);
        patch.decimal("amount", BigDecimal.ZERO, BigDecimal.TEN, 2);

        assertThatThrownBy(patch::throwIfInvalid)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.violations()).hasSize(3));
    }

    @Test
    void decimalsAreCheckedForRangeScaleAndNotation() {
        assertThatThrownBy(() -> {
                    MergePatch p = patch("{\"amount\":\"1.234\"}");
                    p.decimal("amount", BigDecimal.ZERO, BigDecimal.TEN, 2);
                    p.throwIfInvalid();
                })
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> {
                    MergePatch p = patch("{\"amount\":\"1e3\"}");
                    p.decimal("amount", BigDecimal.ZERO, BigDecimal.TEN.pow(4), 2);
                    p.throwIfInvalid();
                })
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> {
                    MergePatch p = patch("{\"amount\":\"11\"}");
                    p.decimal("amount", BigDecimal.ZERO, BigDecimal.TEN, 2);
                    p.throwIfInvalid();
                })
                .isInstanceOf(ApiException.class);
        MergePatch required = patch("{\"day\":null}");
        required.date("day", true);
        assertThatThrownBy(required::throwIfInvalid).isInstanceOf(ApiException.class);
    }
}
