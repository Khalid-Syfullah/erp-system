package com.erp.sales.persistence;

import static com.erp.db.sales.Tables.PRICE_LISTS;
import static com.erp.db.sales.Tables.PRICE_LIST_ITEMS;

import com.erp.db.sales.tables.records.PriceListItemsRecord;
import com.erp.db.sales.tables.records.PriceListsRecord;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.sales.application.SalesListings;
import com.erp.sales.application.SalesViews;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Price lists and their items (SAL-1). */
@Repository
public class PriceListRepository {

    private static final ListBinding LIST_BINDING = ListBinding.builder(SalesListings.PRICE_LISTS)
            .field("code", PRICE_LISTS.CODE)
            .field("name", PRICE_LISTS.NAME)
            .field("currencyCode", PRICE_LISTS.CURRENCY_CODE)
            .field("customerGroupId", PRICE_LISTS.CUSTOMER_GROUP_ID)
            .field("isDefault", PRICE_LISTS.IS_DEFAULT)
            .field("isActive", PRICE_LISTS.IS_ACTIVE)
            .tiebreaker(PRICE_LISTS.ID)
            .search(List.of(PRICE_LISTS.CODE, PRICE_LISTS.NAME))
            .build();

    private static final ListBinding ITEM_BINDING = ListBinding.builder(SalesListings.PRICE_LIST_ITEMS)
            .field("minQuantity", PRICE_LIST_ITEMS.MIN_QUANTITY)
            .field("createdAt", PRICE_LIST_ITEMS.CREATED_AT)
            .field("variantId", PRICE_LIST_ITEMS.VARIANT_ID)
            .tiebreaker(PRICE_LIST_ITEMS.ID)
            .build();

    /** Values of a price list. */
    public record ListValues(
            String code,
            String name,
            String currencyCode,
            boolean pricesIncludeTax,
            @Nullable UUID customerGroupId,
            boolean isDefault,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            boolean active) {}

    public record ItemValues(
            UUID variantId,
            UUID uomId,
            BigDecimal minQuantity,
            BigDecimal unitPrice,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo) {}

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PriceListRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, ListValues v, UUID actor) {
        return dsl.insertInto(PRICE_LISTS)
                .set(PRICE_LISTS.COMPANY_ID, companyId)
                .set(PRICE_LISTS.CODE, v.code())
                .set(PRICE_LISTS.NAME, v.name())
                .set(PRICE_LISTS.CURRENCY_CODE, v.currencyCode())
                .set(PRICE_LISTS.PRICES_INCLUDE_TAX, v.pricesIncludeTax())
                .set(PRICE_LISTS.CUSTOMER_GROUP_ID, v.customerGroupId())
                .set(PRICE_LISTS.IS_DEFAULT, v.isDefault())
                .set(PRICE_LISTS.VALID_FROM, v.validFrom())
                .set(PRICE_LISTS.VALID_TO, v.validTo())
                .set(PRICE_LISTS.IS_ACTIVE, v.active())
                .set(PRICE_LISTS.CREATED_BY, actor)
                .set(PRICE_LISTS.UPDATED_BY, actor)
                .returning(PRICE_LISTS.ID)
                .fetchOne(PRICE_LISTS.ID);
    }

    public boolean update(UUID companyId, UUID id, int version, ListValues v, UUID actor) {
        return dsl.update(PRICE_LISTS)
                        .set(PRICE_LISTS.NAME, v.name())
                        .set(PRICE_LISTS.PRICES_INCLUDE_TAX, v.pricesIncludeTax())
                        .set(PRICE_LISTS.CUSTOMER_GROUP_ID, v.customerGroupId())
                        .set(PRICE_LISTS.IS_DEFAULT, v.isDefault())
                        .set(PRICE_LISTS.VALID_FROM, v.validFrom())
                        .set(PRICE_LISTS.VALID_TO, v.validTo())
                        .set(PRICE_LISTS.IS_ACTIVE, v.active())
                        .set(PRICE_LISTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PRICE_LISTS.UPDATED_BY, actor)
                        .set(PRICE_LISTS.VERSION, version + 1)
                        .where(PRICE_LISTS.COMPANY_ID.eq(companyId))
                        .and(PRICE_LISTS.ID.eq(id))
                        .and(PRICE_LISTS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public Optional<SalesViews.PriceList> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PRICE_LISTS)
                .where(PRICE_LISTS.COMPANY_ID.eq(companyId))
                .and(PRICE_LISTS.ID.eq(id))
                .fetchOptional(PriceListRepository::toView);
    }

    public Optional<SalesViews.PriceList> lock(UUID companyId, UUID id) {
        return dsl.selectFrom(PRICE_LISTS)
                .where(PRICE_LISTS.COMPANY_ID.eq(companyId))
                .and(PRICE_LISTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(PriceListRepository::toView);
    }

    public PageResponse<SalesViews.PriceList> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PRICE_LISTS,
                PRICE_LISTS.COMPANY_ID.eq(companyId),
                query,
                LIST_BINDING,
                PriceListRepository::toView);
    }

    /** Active lists in the currency: the customer group's ({@code groupId}) or the default. */
    public List<SalesViews.PriceList> candidates(UUID companyId, String currencyCode, @Nullable UUID groupId) {
        return dsl.selectFrom(PRICE_LISTS)
                .where(PRICE_LISTS.COMPANY_ID.eq(companyId))
                .and(PRICE_LISTS.CURRENCY_CODE.eq(currencyCode))
                .and(PRICE_LISTS.IS_ACTIVE.isTrue())
                .and(
                        groupId == null
                                ? PRICE_LISTS.IS_DEFAULT.isTrue()
                                : PRICE_LISTS.IS_DEFAULT.isTrue().or(PRICE_LISTS.CUSTOMER_GROUP_ID.eq(groupId)))
                .orderBy(PRICE_LISTS.CODE)
                .fetch(PriceListRepository::toView);
    }

    /**
     * Clears the default flag of the currency's other lists (one default per currency); returns the
     * lists changed.
     */
    public List<SalesViews.PriceList> clearDefault(
            UUID companyId, String currencyCode, @Nullable UUID except, UUID actor) {
        return dsl.update(PRICE_LISTS)
                .set(PRICE_LISTS.IS_DEFAULT, false)
                .set(PRICE_LISTS.UPDATED_AT, OffsetDateTime.now())
                .set(PRICE_LISTS.UPDATED_BY, actor)
                .set(PRICE_LISTS.VERSION, PRICE_LISTS.VERSION.add(1))
                .where(PRICE_LISTS.COMPANY_ID.eq(companyId))
                .and(PRICE_LISTS.CURRENCY_CODE.eq(currencyCode))
                .and(PRICE_LISTS.IS_DEFAULT.isTrue())
                .and(except == null ? org.jooq.impl.DSL.noCondition() : PRICE_LISTS.ID.ne(except))
                .returning()
                .fetch(PriceListRepository::toView);
    }

    // ---------------------------------------------------------------------------------- items

    public UUID insertItem(UUID companyId, UUID listId, ItemValues v, UUID actor) {
        return dsl.insertInto(PRICE_LIST_ITEMS)
                .set(PRICE_LIST_ITEMS.COMPANY_ID, companyId)
                .set(PRICE_LIST_ITEMS.PRICE_LIST_ID, listId)
                .set(PRICE_LIST_ITEMS.VARIANT_ID, v.variantId())
                .set(PRICE_LIST_ITEMS.UOM_ID, v.uomId())
                .set(PRICE_LIST_ITEMS.MIN_QUANTITY, v.minQuantity())
                .set(PRICE_LIST_ITEMS.UNIT_PRICE, v.unitPrice())
                .set(PRICE_LIST_ITEMS.VALID_FROM, v.validFrom())
                .set(PRICE_LIST_ITEMS.VALID_TO, v.validTo())
                .set(PRICE_LIST_ITEMS.CREATED_BY, actor)
                .set(PRICE_LIST_ITEMS.UPDATED_BY, actor)
                .returning(PRICE_LIST_ITEMS.ID)
                .fetchOne(PRICE_LIST_ITEMS.ID);
    }

    public boolean updateItem(UUID companyId, UUID listId, UUID id, int version, ItemValues v, UUID actor) {
        return dsl.update(PRICE_LIST_ITEMS)
                        .set(PRICE_LIST_ITEMS.MIN_QUANTITY, v.minQuantity())
                        .set(PRICE_LIST_ITEMS.UNIT_PRICE, v.unitPrice())
                        .set(PRICE_LIST_ITEMS.VALID_FROM, v.validFrom())
                        .set(PRICE_LIST_ITEMS.VALID_TO, v.validTo())
                        .set(PRICE_LIST_ITEMS.UPDATED_AT, OffsetDateTime.now())
                        .set(PRICE_LIST_ITEMS.UPDATED_BY, actor)
                        .set(PRICE_LIST_ITEMS.VERSION, version + 1)
                        .where(PRICE_LIST_ITEMS.COMPANY_ID.eq(companyId))
                        .and(PRICE_LIST_ITEMS.PRICE_LIST_ID.eq(listId))
                        .and(PRICE_LIST_ITEMS.ID.eq(id))
                        .and(PRICE_LIST_ITEMS.VERSION.eq(version))
                        .execute()
                == 1;
    }

    public boolean deleteItem(UUID companyId, UUID listId, UUID id) {
        return dsl.deleteFrom(PRICE_LIST_ITEMS)
                        .where(PRICE_LIST_ITEMS.COMPANY_ID.eq(companyId))
                        .and(PRICE_LIST_ITEMS.PRICE_LIST_ID.eq(listId))
                        .and(PRICE_LIST_ITEMS.ID.eq(id))
                        .execute()
                == 1;
    }

    public Optional<SalesViews.PriceListItem> findItem(UUID companyId, UUID listId, UUID id) {
        return dsl.selectFrom(PRICE_LIST_ITEMS)
                .where(PRICE_LIST_ITEMS.COMPANY_ID.eq(companyId))
                .and(PRICE_LIST_ITEMS.PRICE_LIST_ID.eq(listId))
                .and(PRICE_LIST_ITEMS.ID.eq(id))
                .fetchOptional(PriceListRepository::toItem);
    }

    public PageResponse<SalesViews.PriceListItem> items(UUID companyId, UUID listId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PRICE_LIST_ITEMS,
                PRICE_LIST_ITEMS.COMPANY_ID.eq(companyId).and(PRICE_LIST_ITEMS.PRICE_LIST_ID.eq(listId)),
                query,
                ITEM_BINDING,
                PriceListRepository::toItem);
    }

    /** All tiers of a variant in a unit on a list. */
    public List<SalesViews.PriceListItem> tiers(UUID companyId, UUID listId, UUID variantId, UUID uomId) {
        return dsl.selectFrom(PRICE_LIST_ITEMS)
                .where(PRICE_LIST_ITEMS.COMPANY_ID.eq(companyId))
                .and(PRICE_LIST_ITEMS.PRICE_LIST_ID.eq(listId))
                .and(PRICE_LIST_ITEMS.VARIANT_ID.eq(variantId))
                .and(PRICE_LIST_ITEMS.UOM_ID.eq(uomId))
                .fetch(PriceListRepository::toItem);
    }

    static SalesViews.PriceList toView(PriceListsRecord r) {
        return new SalesViews.PriceList(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getCurrencyCode(),
                r.getPricesIncludeTax(),
                r.getCustomerGroupId(),
                r.getIsDefault(),
                r.getValidFrom(),
                r.getValidTo(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static SalesViews.PriceListItem toItem(PriceListItemsRecord r) {
        return new SalesViews.PriceListItem(
                r.getId(),
                r.getPriceListId(),
                r.getVariantId(),
                r.getUomId(),
                r.getMinQuantity(),
                r.getUnitPrice(),
                r.getValidFrom(),
                r.getValidTo(),
                r.getVersion());
    }
}
