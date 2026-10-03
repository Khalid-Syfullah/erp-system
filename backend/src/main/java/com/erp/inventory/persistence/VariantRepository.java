package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.PRODUCTS;
import static com.erp.db.inventory.Tables.PRODUCT_VARIANTS;
import static com.erp.db.inventory.Tables.VARIANT_ATTRIBUTE_VALUES;

import com.erp.db.inventory.tables.records.ProductVariantsRecord;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Product variants (SKUs) and their attribute values. */
@Repository
public class VariantRepository {

    private static final ListBinding BINDING = ListBinding.builder(InventoryListings.VARIANTS)
            .field("sku", PRODUCT_VARIANTS.SKU)
            .field("name", PRODUCT_VARIANTS.NAME)
            .field("createdAt", PRODUCT_VARIANTS.CREATED_AT)
            .field("productId", PRODUCT_VARIANTS.PRODUCT_ID)
            .field("barcode", PRODUCT_VARIANTS.BARCODE)
            .field("status", PRODUCT_VARIANTS.STATUS)
            .tiebreaker(PRODUCT_VARIANTS.ID)
            .search(List.of(PRODUCT_VARIANTS.SKU, PRODUCT_VARIANTS.BARCODE, PRODUCT_VARIANTS.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public VariantRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(
            UUID companyId,
            UUID productId,
            String sku,
            @Nullable String barcode,
            String name,
            boolean isDefault,
            @Nullable BigDecimal weightKg,
            Map<UUID, UUID> attributeValues,
            UUID actor) {
        UUID id = dsl.insertInto(PRODUCT_VARIANTS)
                .set(PRODUCT_VARIANTS.COMPANY_ID, companyId)
                .set(PRODUCT_VARIANTS.PRODUCT_ID, productId)
                .set(PRODUCT_VARIANTS.SKU, sku)
                .set(PRODUCT_VARIANTS.BARCODE, barcode)
                .set(PRODUCT_VARIANTS.NAME, name)
                .set(PRODUCT_VARIANTS.IS_DEFAULT, isDefault)
                .set(PRODUCT_VARIANTS.WEIGHT_KG, weightKg)
                .set(PRODUCT_VARIANTS.ATTRIBUTE_SIGNATURE, signature(attributeValues))
                .set(PRODUCT_VARIANTS.CREATED_BY, actor)
                .set(PRODUCT_VARIANTS.UPDATED_BY, actor)
                .returning(PRODUCT_VARIANTS.ID)
                .fetchOne(PRODUCT_VARIANTS.ID);
        attributeValues.forEach((attribute, value) -> dsl.insertInto(VARIANT_ATTRIBUTE_VALUES)
                .set(VARIANT_ATTRIBUTE_VALUES.VARIANT_ID, id)
                .set(VARIANT_ATTRIBUTE_VALUES.ATTRIBUTE_ID, attribute)
                .set(VARIANT_ATTRIBUTE_VALUES.VALUE_ID, value)
                .set(VARIANT_ATTRIBUTE_VALUES.COMPANY_ID, companyId)
                .execute());
        return id;
    }

    /** Sorted "attributeId=valueId;…": equal combinations collide on the unique index. */
    static String signature(Map<UUID, UUID> attributeValues) {
        return attributeValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
    }

    public Optional<InventoryViews.Variant> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCT_VARIANTS)
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.ID.eq(id))
                .fetchOptional()
                .map(r ->
                        toView(r, attributeValues(companyId, List.of(r.getId())).getOrDefault(r.getId(), Map.of())));
    }

    public Optional<InventoryViews.Variant> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCT_VARIANTS)
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(r ->
                        toView(r, attributeValues(companyId, List.of(r.getId())).getOrDefault(r.getId(), Map.of())));
    }

    public PageResponse<InventoryViews.Variant> list(UUID companyId, @Nullable UUID productId, ListQuery query) {
        Condition scope = PRODUCT_VARIANTS.COMPANY_ID.eq(companyId);
        if (productId != null) {
            scope = scope.and(PRODUCT_VARIANTS.PRODUCT_ID.eq(productId));
        }
        PageResponse<ProductVariantsRecord> page =
                paginator.fetch(dsl, PRODUCT_VARIANTS, scope, query, BINDING, r -> r);
        Map<UUID, Map<UUID, UUID>> values = attributeValues(
                companyId,
                page.data().stream().map(ProductVariantsRecord::getId).toList());
        return page.map(r -> toView(r, values.getOrDefault(r.getId(), Map.of())));
    }

    public List<InventoryViews.Variant> forProduct(UUID companyId, UUID productId) {
        List<ProductVariantsRecord> rows = dsl.selectFrom(PRODUCT_VARIANTS)
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.PRODUCT_ID.eq(productId))
                .orderBy(PRODUCT_VARIANTS.SKU)
                .fetch();
        Map<UUID, Map<UUID, UUID>> values = attributeValues(
                companyId, rows.stream().map(ProductVariantsRecord::getId).toList());
        return rows.stream()
                .map(r -> toView(r, values.getOrDefault(r.getId(), Map.of())))
                .toList();
    }

    public boolean update(
            UUID companyId,
            UUID id,
            int version,
            UUID actor,
            String sku,
            @Nullable String barcode,
            String name,
            @Nullable BigDecimal weightKg,
            String status) {
        return dsl.update(PRODUCT_VARIANTS)
                        .set(PRODUCT_VARIANTS.SKU, sku)
                        .set(PRODUCT_VARIANTS.BARCODE, barcode)
                        .set(PRODUCT_VARIANTS.NAME, name)
                        .set(PRODUCT_VARIANTS.WEIGHT_KG, weightKg)
                        .set(PRODUCT_VARIANTS.STATUS, status)
                        .set(PRODUCT_VARIANTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PRODUCT_VARIANTS.UPDATED_BY, actor)
                        .set(PRODUCT_VARIANTS.VERSION, version + 1)
                        .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                        .and(PRODUCT_VARIANTS.ID.eq(id))
                        .and(PRODUCT_VARIANTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    /** Archives or reactivates all variants of a product (with the product). */
    public void setStatusForProduct(UUID companyId, UUID productId, String status, UUID actor) {
        dsl.update(PRODUCT_VARIANTS)
                .set(PRODUCT_VARIANTS.STATUS, status)
                .set(PRODUCT_VARIANTS.UPDATED_AT, OffsetDateTime.now())
                .set(PRODUCT_VARIANTS.UPDATED_BY, actor)
                .set(PRODUCT_VARIANTS.VERSION, PRODUCT_VARIANTS.VERSION.plus(1))
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.PRODUCT_ID.eq(productId))
                .and(PRODUCT_VARIANTS.STATUS.ne(status))
                .execute();
    }

    /**
     * Variants with their product's stock-relevant data, locked {@code FOR SHARE} in ID order so that
     * archiving waits for postings in progress.
     */
    public Map<UUID, InventoryViews.StockItem> lockItems(UUID companyId, Collection<UUID> variantIds) {
        Map<UUID, InventoryViews.StockItem> items = new LinkedHashMap<>();
        dsl.select(
                        PRODUCT_VARIANTS.ID,
                        PRODUCT_VARIANTS.PRODUCT_ID,
                        PRODUCT_VARIANTS.SKU,
                        PRODUCT_VARIANTS.STATUS,
                        PRODUCTS.STATUS,
                        PRODUCTS.PRODUCT_TYPE,
                        PRODUCTS.CATEGORY_ID,
                        PRODUCTS.BASE_UOM_ID)
                .from(PRODUCT_VARIANTS)
                .join(PRODUCTS)
                .on(PRODUCTS.COMPANY_ID.eq(PRODUCT_VARIANTS.COMPANY_ID))
                .and(PRODUCTS.ID.eq(PRODUCT_VARIANTS.PRODUCT_ID))
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.ID.in(variantIds))
                .orderBy(PRODUCT_VARIANTS.ID)
                .forShare()
                .of(PRODUCT_VARIANTS)
                .fetch()
                .forEach(r -> items.put(
                        r.value1(),
                        new InventoryViews.StockItem(
                                r.value1(),
                                r.value2(),
                                r.value3(),
                                r.value4(),
                                r.value5(),
                                r.value6(),
                                r.value7(),
                                r.value8())));
        return items;
    }

    /** Variants with product data, without locking (read paths). */
    public Map<UUID, InventoryViews.StockItem> items(UUID companyId, Collection<UUID> variantIds) {
        Map<UUID, InventoryViews.StockItem> items = new HashMap<>();
        if (variantIds.isEmpty()) {
            return items;
        }
        dsl.select(
                        PRODUCT_VARIANTS.ID,
                        PRODUCT_VARIANTS.PRODUCT_ID,
                        PRODUCT_VARIANTS.SKU,
                        PRODUCT_VARIANTS.STATUS,
                        PRODUCTS.STATUS,
                        PRODUCTS.PRODUCT_TYPE,
                        PRODUCTS.CATEGORY_ID,
                        PRODUCTS.BASE_UOM_ID)
                .from(PRODUCT_VARIANTS)
                .join(PRODUCTS)
                .on(PRODUCTS.COMPANY_ID.eq(PRODUCT_VARIANTS.COMPANY_ID))
                .and(PRODUCTS.ID.eq(PRODUCT_VARIANTS.PRODUCT_ID))
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.ID.in(variantIds))
                .fetch()
                .forEach(r -> items.put(
                        r.value1(),
                        new InventoryViews.StockItem(
                                r.value1(),
                                r.value2(),
                                r.value3(),
                                r.value4(),
                                r.value5(),
                                r.value6(),
                                r.value7(),
                                r.value8())));
        return items;
    }

    private Map<UUID, Map<UUID, UUID>> attributeValues(UUID companyId, Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<UUID, UUID>> result = new HashMap<>();
        dsl.selectFrom(VARIANT_ATTRIBUTE_VALUES)
                .where(VARIANT_ATTRIBUTE_VALUES.COMPANY_ID.eq(companyId))
                .and(VARIANT_ATTRIBUTE_VALUES.VARIANT_ID.in(variantIds))
                .fetch()
                .forEach(r -> result.computeIfAbsent(r.getVariantId(), k -> new LinkedHashMap<>())
                        .put(r.getAttributeId(), r.getValueId()));
        return result;
    }

    static InventoryViews.Variant toView(ProductVariantsRecord r, Map<UUID, UUID> attributeValues) {
        return new InventoryViews.Variant(
                r.getId(),
                r.getCompanyId(),
                r.getProductId(),
                r.getSku(),
                r.getBarcode(),
                r.getName(),
                r.getIsDefault(),
                r.getStatus(),
                r.getWeightKg(),
                attributeValues,
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
