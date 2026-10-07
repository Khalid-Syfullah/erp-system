package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.INVENTORY_TRANSACTIONS;
import static com.erp.db.inventory.Tables.PRODUCTS;
import static com.erp.db.inventory.Tables.PRODUCT_UOM_CONVERSIONS;
import static com.erp.db.inventory.Tables.PRODUCT_VARIANTS;
import static com.erp.db.inventory.Tables.STOCK_BALANCES;

import com.erp.db.inventory.tables.records.ProductsRecord;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Products (templates) and their unit conversions. */
@Repository
public class ProductRepository {

    private static final ListBinding BINDING = ListBinding.builder(InventoryListings.PRODUCTS)
            .field("id", PRODUCTS.ID)
            .field("code", PRODUCTS.CODE)
            .field("name", PRODUCTS.NAME)
            .field("createdAt", PRODUCTS.CREATED_AT)
            .field("categoryId", PRODUCTS.CATEGORY_ID)
            .field("productType", PRODUCTS.PRODUCT_TYPE)
            .field("status", PRODUCTS.STATUS)
            .field("isPurchasable", PRODUCTS.IS_PURCHASABLE)
            .field("isSellable", PRODUCTS.IS_SELLABLE)
            .tiebreaker(PRODUCTS.ID)
            .search(List.of(PRODUCTS.CODE, PRODUCTS.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public ProductRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, InventoryCommands.Product c, UUID actor) {
        return dsl.insertInto(PRODUCTS)
                .set(PRODUCTS.COMPANY_ID, companyId)
                .set(PRODUCTS.CODE, c.code())
                .set(PRODUCTS.NAME, c.name())
                .set(PRODUCTS.DESCRIPTION, c.description())
                .set(PRODUCTS.CATEGORY_ID, c.categoryId())
                .set(PRODUCTS.PRODUCT_TYPE, c.productType())
                .set(PRODUCTS.BASE_UOM_ID, c.baseUomId())
                .set(PRODUCTS.PURCHASE_UOM_ID, c.purchaseUomId())
                .set(PRODUCTS.SALES_UOM_ID, c.salesUomId())
                .set(PRODUCTS.IS_PURCHASABLE, c.purchasable())
                .set(PRODUCTS.IS_SELLABLE, c.sellable())
                .set(PRODUCTS.SALES_TAX_CODE_ID, c.salesTaxCodeId())
                .set(PRODUCTS.PURCHASE_TAX_CODE_ID, c.purchaseTaxCodeId())
                .set(PRODUCTS.HAS_VARIANTS, c.hasVariants())
                .set(PRODUCTS.CREATED_BY, actor)
                .set(PRODUCTS.UPDATED_BY, actor)
                .returning(PRODUCTS.ID)
                .fetchOne(PRODUCTS.ID);
    }

    public Optional<InventoryViews.Product> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCTS)
                .where(PRODUCTS.COMPANY_ID.eq(companyId))
                .and(PRODUCTS.ID.eq(id))
                .fetchOptional(ProductRepository::toView);
    }

    public Optional<InventoryViews.Product> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCTS)
                .where(PRODUCTS.COMPANY_ID.eq(companyId))
                .and(PRODUCTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(ProductRepository::toView);
    }

    public PageResponse<InventoryViews.Product> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl, PRODUCTS, PRODUCTS.COMPANY_ID.eq(companyId), query, BINDING, ProductRepository::toView);
    }

    public boolean update(
            UUID companyId, UUID id, int version, UUID actor, InventoryCommands.Product c, String status) {
        return dsl.update(PRODUCTS)
                        .set(PRODUCTS.NAME, c.name())
                        .set(PRODUCTS.DESCRIPTION, c.description())
                        .set(PRODUCTS.CATEGORY_ID, c.categoryId())
                        .set(PRODUCTS.BASE_UOM_ID, c.baseUomId())
                        .set(PRODUCTS.PURCHASE_UOM_ID, c.purchaseUomId())
                        .set(PRODUCTS.SALES_UOM_ID, c.salesUomId())
                        .set(PRODUCTS.IS_PURCHASABLE, c.purchasable())
                        .set(PRODUCTS.IS_SELLABLE, c.sellable())
                        .set(PRODUCTS.SALES_TAX_CODE_ID, c.salesTaxCodeId())
                        .set(PRODUCTS.PURCHASE_TAX_CODE_ID, c.purchaseTaxCodeId())
                        .set(PRODUCTS.STATUS, status)
                        .set(PRODUCTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PRODUCTS.UPDATED_BY, actor)
                        .set(PRODUCTS.VERSION, version + 1)
                        .where(PRODUCTS.COMPANY_ID.eq(companyId))
                        .and(PRODUCTS.ID.eq(id))
                        .and(PRODUCTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    /** Whether any inventory transaction exists for the product's variants (base unit locked). */
    public boolean hasTransactions(UUID companyId, UUID productId) {
        return dsl.fetchExists(DSL.selectOne()
                .from(INVENTORY_TRANSACTIONS)
                .join(PRODUCT_VARIANTS)
                .on(PRODUCT_VARIANTS.COMPANY_ID.eq(INVENTORY_TRANSACTIONS.COMPANY_ID))
                .and(PRODUCT_VARIANTS.ID.eq(INVENTORY_TRANSACTIONS.VARIANT_ID))
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.PRODUCT_ID.eq(productId)));
    }

    /** Stock on hand anywhere for the product's variants. */
    public boolean hasStock(UUID companyId, UUID productId) {
        return dsl.fetchExists(DSL.selectOne()
                .from(STOCK_BALANCES)
                .join(PRODUCT_VARIANTS)
                .on(PRODUCT_VARIANTS.COMPANY_ID.eq(STOCK_BALANCES.COMPANY_ID))
                .and(PRODUCT_VARIANTS.ID.eq(STOCK_BALANCES.VARIANT_ID))
                .where(PRODUCT_VARIANTS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_VARIANTS.PRODUCT_ID.eq(productId))
                .and(STOCK_BALANCES.ON_HAND.gt(BigDecimal.ZERO)));
    }

    // ---------------------------------------------------------------- unit conversions

    public List<InventoryViews.UomConversion> conversions(UUID companyId, UUID productId) {
        return dsl.selectFrom(PRODUCT_UOM_CONVERSIONS)
                .where(PRODUCT_UOM_CONVERSIONS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_UOM_CONVERSIONS.PRODUCT_ID.eq(productId))
                .orderBy(PRODUCT_UOM_CONVERSIONS.CREATED_AT)
                .fetch(r -> new InventoryViews.UomConversion(
                        r.getId(),
                        r.getProductId(),
                        r.getUomId(),
                        r.getFactorToBase(),
                        r.getCreatedAt(),
                        r.getVersion()));
    }

    /** Product-specific factors: unit → base units per unit. */
    public Map<UUID, BigDecimal> conversionFactors(UUID companyId, UUID productId) {
        return conversions(companyId, productId).stream()
                .collect(Collectors.toMap(
                        InventoryViews.UomConversion::uomId, InventoryViews.UomConversion::factorToBase));
    }

    public UUID insertConversion(UUID companyId, UUID productId, UUID uomId, BigDecimal factor, UUID actor) {
        return dsl.insertInto(PRODUCT_UOM_CONVERSIONS)
                .set(PRODUCT_UOM_CONVERSIONS.COMPANY_ID, companyId)
                .set(PRODUCT_UOM_CONVERSIONS.PRODUCT_ID, productId)
                .set(PRODUCT_UOM_CONVERSIONS.UOM_ID, uomId)
                .set(PRODUCT_UOM_CONVERSIONS.FACTOR_TO_BASE, factor)
                .set(PRODUCT_UOM_CONVERSIONS.CREATED_BY, actor)
                .set(PRODUCT_UOM_CONVERSIONS.UPDATED_BY, actor)
                .returning(PRODUCT_UOM_CONVERSIONS.ID)
                .fetchOne(PRODUCT_UOM_CONVERSIONS.ID);
    }

    public Optional<InventoryViews.UomConversion> findConversion(UUID companyId, UUID productId, UUID conversionId) {
        return conversions(companyId, productId).stream()
                .filter(c -> c.id().equals(conversionId))
                .findFirst();
    }

    public int deleteConversion(UUID companyId, UUID conversionId) {
        return dsl.deleteFrom(PRODUCT_UOM_CONVERSIONS)
                .where(PRODUCT_UOM_CONVERSIONS.COMPANY_ID.eq(companyId))
                .and(PRODUCT_UOM_CONVERSIONS.ID.eq(conversionId))
                .execute();
    }

    static InventoryViews.Product toView(ProductsRecord r) {
        return new InventoryViews.Product(
                r.getId(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getDescription(),
                r.getCategoryId(),
                r.getProductType(),
                r.getBaseUomId(),
                r.getPurchaseUomId(),
                r.getSalesUomId(),
                r.getIsPurchasable(),
                r.getIsSellable(),
                r.getSalesTaxCodeId(),
                r.getPurchaseTaxCodeId(),
                r.getHasVariants(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
