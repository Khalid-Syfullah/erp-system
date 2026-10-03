package com.erp.inventory.persistence;

import static com.erp.db.inventory.Tables.UOMS;
import static com.erp.db.inventory.Tables.UOM_CATEGORIES;

import com.erp.inventory.application.InventoryViews;
import com.erp.inventory.domain.UomConversion;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Global units of measure (read-only; seeded by migration). */
@Repository
public class UomRepository {

    private final DSLContext dsl;

    public UomRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<InventoryViews.UomCategory> categories() {
        return dsl.selectFrom(UOM_CATEGORIES)
                .orderBy(UOM_CATEGORIES.CODE)
                .fetch(r -> new InventoryViews.UomCategory(r.getId(), r.getCode(), r.getName()));
    }

    public List<InventoryViews.Uom> all() {
        return dsl.selectFrom(UOMS).orderBy(UOMS.CODE).fetch(UomRepository::toView);
    }

    public Optional<InventoryViews.Uom> find(UUID id) {
        return dsl.selectFrom(UOMS).where(UOMS.ID.eq(id)).fetchOptional(UomRepository::toView);
    }

    public Map<UUID, UomConversion.Unit> units(Collection<UUID> ids) {
        return dsl
                .selectFrom(UOMS)
                .where(UOMS.ID.in(ids))
                .fetch(r -> new UomConversion.Unit(
                        r.getId(), r.getCode(), r.getCategoryId(), r.getFactorToReference(), r.getRoundingScale()))
                .stream()
                .collect(Collectors.toMap(UomConversion.Unit::id, Function.identity()));
    }

    private static InventoryViews.Uom toView(com.erp.db.inventory.tables.records.UomsRecord r) {
        return new InventoryViews.Uom(
                r.getId(),
                r.getCategoryId(),
                r.getCode(),
                r.getName(),
                r.getFactorToReference(),
                r.getRoundingScale(),
                r.getIsActive());
    }
}
