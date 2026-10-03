package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.PRODUCTS;
import static com.erp.db.inventory.Tables.PRODUCT_CATEGORIES;

import com.erp.db.inventory.tables.records.ProductCategoriesRecord;
import com.erp.inventory.application.InventoryCommands;
import com.erp.inventory.application.InventoryListings;
import com.erp.inventory.application.InventoryViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Product categories: a tree with a trigger-maintained ltree path (DATABASE.md §8.2). */
@Repository
public class CategoryRepository {

    private static final ListBinding BINDING = ListBinding.builder(InventoryListings.CATEGORIES)
            .field("code", PRODUCT_CATEGORIES.CODE)
            .field("name", PRODUCT_CATEGORIES.NAME)
            .field("createdAt", PRODUCT_CATEGORIES.CREATED_AT)
            .field("parentId", PRODUCT_CATEGORIES.PARENT_ID)
            .field("isActive", PRODUCT_CATEGORIES.IS_ACTIVE)
            .tiebreaker(PRODUCT_CATEGORIES.ID)
            .search(List.of(PRODUCT_CATEGORIES.CODE, PRODUCT_CATEGORIES.NAME))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public CategoryRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, InventoryCommands.Category c, UUID actor) {
        return dsl.insertInto(PRODUCT_CATEGORIES)
                .set(PRODUCT_CATEGORIES.COMPANY_ID, companyId)
                .set(PRODUCT_CATEGORIES.CODE, c.code())
                .set(PRODUCT_CATEGORIES.NAME, c.name())
                .set(PRODUCT_CATEGORIES.PARENT_ID, c.parentId())
                .set(PRODUCT_CATEGORIES.CREATED_BY, actor)
                .set(PRODUCT_CATEGORIES.UPDATED_BY, actor)
                .returning(PRODUCT_CATEGORIES.ID)
                .fetchOne(PRODUCT_CATEGORIES.ID);
    }

    public Optional<InventoryViews.Category> find(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCT_CATEGORIES)
                .where(PRODUCT_CATEGORIES.COMPANY_ID.eq(companyId))
                .and(PRODUCT_CATEGORIES.ID.eq(id))
                .fetchOptional(CategoryRepository::toView);
    }

    public Optional<InventoryViews.Category> lockForChange(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCT_CATEGORIES)
                .where(PRODUCT_CATEGORIES.COMPANY_ID.eq(companyId))
                .and(PRODUCT_CATEGORIES.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(CategoryRepository::toView);
    }

    public Optional<InventoryViews.Category> lockForUse(UUID companyId, UUID id) {
        return dsl.selectFrom(PRODUCT_CATEGORIES)
                .where(PRODUCT_CATEGORIES.COMPANY_ID.eq(companyId))
                .and(PRODUCT_CATEGORIES.ID.eq(id))
                .forShare()
                .fetchOptional(CategoryRepository::toView);
    }

    public PageResponse<InventoryViews.Category> list(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PRODUCT_CATEGORIES,
                PRODUCT_CATEGORIES.COMPANY_ID.eq(companyId),
                query,
                BINDING,
                CategoryRepository::toView);
    }

    public boolean update(UUID companyId, UUID id, int version, UUID actor, InventoryCommands.Category c) {
        return dsl.update(PRODUCT_CATEGORIES)
                        .set(PRODUCT_CATEGORIES.NAME, c.name())
                        .set(PRODUCT_CATEGORIES.PARENT_ID, c.parentId())
                        .set(PRODUCT_CATEGORIES.IS_ACTIVE, c.active())
                        .set(PRODUCT_CATEGORIES.UPDATED_AT, OffsetDateTime.now())
                        .set(PRODUCT_CATEGORIES.UPDATED_BY, actor)
                        .set(PRODUCT_CATEGORIES.VERSION, version + 1)
                        .where(PRODUCT_CATEGORIES.COMPANY_ID.eq(companyId))
                        .and(PRODUCT_CATEGORIES.ID.eq(id))
                        .and(PRODUCT_CATEGORIES.VERSION.eq(version))
                        .execute()
                == 1;
    }

    /** Whether {@code candidate} is {@code ancestor} or lies below it (via the ltree path). */
    public boolean isSelfOrDescendant(UUID companyId, UUID ancestor, UUID candidate) {
        var a = PRODUCT_CATEGORIES.as("a");
        var c = PRODUCT_CATEGORIES.as("c");
        return dsl.fetchExists(DSL.selectOne()
                .from(a)
                .join(c)
                .on(c.COMPANY_ID.eq(a.COMPANY_ID))
                .where(a.COMPANY_ID.eq(companyId))
                .and(a.ID.eq(ancestor))
                .and(c.ID.eq(candidate))
                .and(DSL.condition("{0} <@ {1}", c.PATH, a.PATH)));
    }

    public int countActiveChildren(UUID companyId, UUID id) {
        return dsl.fetchCount(
                PRODUCT_CATEGORIES,
                PRODUCT_CATEGORIES
                        .COMPANY_ID
                        .eq(companyId)
                        .and(PRODUCT_CATEGORIES.PARENT_ID.eq(id))
                        .and(PRODUCT_CATEGORIES.IS_ACTIVE.isTrue()));
    }

    public int countActiveProducts(UUID companyId, UUID id) {
        return dsl.fetchCount(
                PRODUCTS,
                PRODUCTS.COMPANY_ID
                        .eq(companyId)
                        .and(PRODUCTS.CATEGORY_ID.eq(id))
                        .and(PRODUCTS.STATUS.eq("ACTIVE")));
    }

    static InventoryViews.Category toView(ProductCategoriesRecord r) {
        return new InventoryViews.Category(
                r.getId(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getParentId(),
                r.getPath().data(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }
}
