package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.PRODUCT_ATTRIBUTES;
import static com.erp.db.inventory.Tables.PRODUCT_ATTRIBUTE_VALUES;

import com.erp.db.inventory.tables.records.ProductAttributesRecord;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Product attributes (e.g. Size, Color) and their values. */
@Repository
public class AttributeRepository {

    private static final ListBinding BINDING = ListBinding.builder(InventoryListings.ATTRIBUTES)
            .field("code", PRODUCT_ATTRIBUTES.CODE)
            .field("name", PRODUCT_ATTRIBUTES.NAME)
            .tiebreaker(PRODUCT_ATTRIBUTES.ID)
            .search(List.of(PRODUCT_ATTRIBUTES.CODE, PRODUCT_ATTRIBUTES.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public AttributeRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, String code, String name, UUID actor) {
        return dsl.insertInto(PRODUCT_ATTRIBUTES)
                .set(PRODUCT_ATTRIBUTES.COMPANY_ID, companyId)
                .set(PRODUCT_ATTRIBUTES.CODE, code)
                .set(PRODUCT_ATTRIBUTES.NAME, name)
                .set(PRODUCT_ATTRIBUTES.CREATED_BY, actor)
                .set(PRODUCT_ATTRIBUTES.UPDATED_BY, actor)
                .returning(PRODUCT_ATTRIBUTES.ID)
                .fetchOne(PRODUCT_ATTRIBUTES.ID);
    }

    public UUID insertValue(UUID companyId, UUID attributeId, String code, String name, int sortOrder, UUID actor) {
        return dsl.insertInto(PRODUCT_ATTRIBUTE_VALUES)
                .set(PRODUCT_ATTRIBUTE_VALUES.COMPANY_ID, companyId)
                .set(PRODUCT_ATTRIBUTE_VALUES.ATTRIBUTE_ID, attributeId)
                .set(PRODUCT_ATTRIBUTE_VALUES.CODE, code)
                .set(PRODUCT_ATTRIBUTE_VALUES.NAME, name)
                .set(PRODUCT_ATTRIBUTE_VALUES.SORT_ORDER, sortOrder)
                .set(PRODUCT_ATTRIBUTE_VALUES.CREATED_BY, actor)
                .set(PRODUCT_ATTRIBUTE_VALUES.UPDATED_BY, actor)
                .returning(PRODUCT_ATTRIBUTE_VALUES.ID)
                .fetchOne(PRODUCT_ATTRIBUTE_VALUES.ID);
    }

    public Optional<InventoryViews.Attribute> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCT_ATTRIBUTES)
                .where(PRODUCT_ATTRIBUTES.COMPANY_ID.eq(companyId))
                .and(PRODUCT_ATTRIBUTES.ID.eq(id))
                .fetchOptional()
                .map(r -> toView(r, values(companyId, List.of(r.getId())).getOrDefault(r.getId(), List.of())));
    }

    public PageResponse<InventoryViews.Attribute> list(UUID companyId, ListQuery query) {
        PageResponse<ProductAttributesRecord> page = paginator.fetch(
                dsl, PRODUCT_ATTRIBUTES, PRODUCT_ATTRIBUTES.COMPANY_ID.eq(companyId), query, BINDING, r -> r);
        Map<UUID, List<InventoryViews.AttributeValue>> values = values(
                companyId,
                page.data().stream().map(ProductAttributesRecord::getId).toList());
        return page.map(r -> toView(r, values.getOrDefault(r.getId(), List.of())));
    }

    /** Attribute values by ID (unknown IDs are skipped). */
    public Map<UUID, InventoryViews.AttributeValue> valuesById(UUID companyId, Collection<UUID> valueIds) {
        if (valueIds.isEmpty()) {
            return Map.of();
        }
        return dsl
                .selectFrom(PRODUCT_ATTRIBUTE_VALUES)
                .where(PRODUCT_ATTRIBUTE_VALUES.COMPANY_ID.eq(companyId))
                .and(PRODUCT_ATTRIBUTE_VALUES.ID.in(valueIds))
                .fetch(AttributeRepository::toValue)
                .stream()
                .collect(Collectors.toMap(InventoryViews.AttributeValue::id, v -> v));
    }

    private Map<UUID, List<InventoryViews.AttributeValue>> values(UUID companyId, Collection<UUID> attributeIds) {
        if (attributeIds.isEmpty()) {
            return Map.of();
        }
        return dsl
                .selectFrom(PRODUCT_ATTRIBUTE_VALUES)
                .where(PRODUCT_ATTRIBUTE_VALUES.COMPANY_ID.eq(companyId))
                .and(PRODUCT_ATTRIBUTE_VALUES.ATTRIBUTE_ID.in(attributeIds))
                .orderBy(PRODUCT_ATTRIBUTE_VALUES.SORT_ORDER, PRODUCT_ATTRIBUTE_VALUES.CODE)
                .fetch(AttributeRepository::toValue)
                .stream()
                .collect(Collectors.groupingBy(InventoryViews.AttributeValue::attributeId));
    }

    private static InventoryViews.AttributeValue toValue(
            com.erp.db.inventory.tables.records.ProductAttributeValuesRecord r) {
        return new InventoryViews.AttributeValue(
                r.getId(), r.getAttributeId(), r.getCode(), r.getName(), r.getSortOrder());
    }

    private static InventoryViews.Attribute toView(
            ProductAttributesRecord r, List<InventoryViews.AttributeValue> values) {
        return new InventoryViews.Attribute(
                r.getId(), r.getCode(), r.getName(), values, r.getCreatedAt(), r.getVersion());
    }
}
