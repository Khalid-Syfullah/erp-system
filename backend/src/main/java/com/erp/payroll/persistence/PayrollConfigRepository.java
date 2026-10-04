package com.erp.payroll.persistence;

import static com.erp.db.payroll.Tables.PAY_COMPONENTS;
import static com.erp.db.payroll.Tables.PAY_SCHEDULES;
import static com.erp.db.payroll.Tables.SALARY_STRUCTURES;
import static com.erp.db.payroll.Tables.SALARY_STRUCTURE_COMPONENTS;
import static com.erp.db.payroll.Tables.SETTINGS;

import com.erp.db.payroll.tables.records.PayComponentsRecord;
import com.erp.db.payroll.tables.records.PaySchedulesRecord;
import com.erp.db.payroll.tables.records.SalaryStructuresRecord;
import com.erp.payroll.application.PayrollCommands;
import com.erp.payroll.application.PayrollListings;
import com.erp.payroll.application.PayrollViews;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Payroll settings, pay components, salary structures and pay schedules (DATABASE.md §5.10). */
@Repository
public class PayrollConfigRepository {

    private static final ListBinding COMPONENT_BINDING = ListBinding.builder(PayrollListings.COMPONENTS)
            .field("sequence", PAY_COMPONENTS.SEQUENCE)
            .field("code", PAY_COMPONENTS.CODE)
            .field("createdAt", PAY_COMPONENTS.CREATED_AT)
            .field("kind", PAY_COMPONENTS.KIND)
            .field("isActive", PAY_COMPONENTS.IS_ACTIVE)
            .tiebreaker(PAY_COMPONENTS.ID)
            .search(List.of(PAY_COMPONENTS.CODE, PAY_COMPONENTS.NAME))
            .build();

    private static final ListBinding STRUCTURE_BINDING = ListBinding.builder(PayrollListings.STRUCTURES)
            .field("code", SALARY_STRUCTURES.CODE)
            .field("createdAt", SALARY_STRUCTURES.CREATED_AT)
            .field("isActive", SALARY_STRUCTURES.IS_ACTIVE)
            .tiebreaker(SALARY_STRUCTURES.ID)
            .search(List.of(SALARY_STRUCTURES.CODE, SALARY_STRUCTURES.NAME))
            .build();

    private static final ListBinding SCHEDULE_BINDING = ListBinding.builder(PayrollListings.SCHEDULES)
            .field("code", PAY_SCHEDULES.CODE)
            .field("createdAt", PAY_SCHEDULES.CREATED_AT)
            .field("isActive", PAY_SCHEDULES.IS_ACTIVE)
            .tiebreaker(PAY_SCHEDULES.ID)
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public PayrollConfigRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    // ------------------------------------------------------------------------------ settings

    public Optional<PayrollViews.Settings> settings(UUID companyId) {
        return dsl.selectFrom(SETTINGS)
                .where(SETTINGS.COMPANY_ID.eq(companyId))
                .fetchOptional(r -> new PayrollViews.Settings(r.getProrationBasis(), r.getVersion()));
    }

    public void ensureSettings(UUID companyId) {
        dsl.insertInto(SETTINGS)
                .set(SETTINGS.COMPANY_ID, companyId)
                .onConflictDoNothing()
                .execute();
    }

    public boolean updateSettings(UUID companyId, int expectedVersion, UUID actor, String prorationBasis) {
        return dsl.update(SETTINGS)
                        .set(SETTINGS.PRORATION_BASIS, prorationBasis)
                        .set(SETTINGS.UPDATED_AT, OffsetDateTime.now())
                        .set(SETTINGS.UPDATED_BY, actor)
                        .set(SETTINGS.VERSION, expectedVersion + 1)
                        .where(SETTINGS.COMPANY_ID.eq(companyId))
                        .and(SETTINGS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    // ---------------------------------------------------------------------------- components

    public UUID insertComponent(UUID companyId, PayrollCommands.Component c, UUID actor) {
        return dsl.insertInto(PAY_COMPONENTS)
                .set(PAY_COMPONENTS.COMPANY_ID, companyId)
                .set(PAY_COMPONENTS.CODE, c.code())
                .set(PAY_COMPONENTS.NAME, c.name())
                .set(PAY_COMPONENTS.KIND, c.kind())
                .set(PAY_COMPONENTS.CALCULATION, c.calculation())
                .set(PAY_COMPONENTS.DEFAULT_RATE, c.defaultRate())
                .set(PAY_COMPONENTS.DEFAULT_AMOUNT, c.defaultAmount())
                .set(PAY_COMPONENTS.IS_TAXABLE, c.taxable())
                .set(PAY_COMPONENTS.STATUTORY_RULE_CODE, c.statutoryRuleCode())
                .set(PAY_COMPONENTS.SEQUENCE, c.sequence())
                .set(PAY_COMPONENTS.IS_ACTIVE, c.active())
                .set(PAY_COMPONENTS.CREATED_BY, actor)
                .set(PAY_COMPONENTS.UPDATED_BY, actor)
                .returning(PAY_COMPONENTS.ID)
                .fetchSingle(PAY_COMPONENTS.ID);
    }

    public boolean updateComponent(
            UUID companyId, UUID id, int expectedVersion, UUID actor, PayrollCommands.Component c) {
        return dsl.update(PAY_COMPONENTS)
                        .set(PAY_COMPONENTS.NAME, c.name())
                        .set(PAY_COMPONENTS.DEFAULT_RATE, c.defaultRate())
                        .set(PAY_COMPONENTS.DEFAULT_AMOUNT, c.defaultAmount())
                        .set(PAY_COMPONENTS.IS_TAXABLE, c.taxable())
                        .set(PAY_COMPONENTS.STATUTORY_RULE_CODE, c.statutoryRuleCode())
                        .set(PAY_COMPONENTS.SEQUENCE, c.sequence())
                        .set(PAY_COMPONENTS.IS_ACTIVE, c.active())
                        .set(PAY_COMPONENTS.UPDATED_AT, OffsetDateTime.now())
                        .set(PAY_COMPONENTS.UPDATED_BY, actor)
                        .set(PAY_COMPONENTS.VERSION, expectedVersion + 1)
                        .where(PAY_COMPONENTS.COMPANY_ID.eq(companyId))
                        .and(PAY_COMPONENTS.ID.eq(id))
                        .and(PAY_COMPONENTS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public Optional<PayrollViews.Component> component(UUID companyId, UUID id) {
        return dsl.selectFrom(PAY_COMPONENTS)
                .where(PAY_COMPONENTS.COMPANY_ID.eq(companyId))
                .and(PAY_COMPONENTS.ID.eq(id))
                .fetchOptional(PayrollConfigRepository::toComponent);
    }

    public Map<UUID, PayrollViews.Component> components(UUID companyId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dsl
                .selectFrom(PAY_COMPONENTS)
                .where(PAY_COMPONENTS.COMPANY_ID.eq(companyId))
                .and(PAY_COMPONENTS.ID.in(ids))
                .fetch(PayrollConfigRepository::toComponent)
                .stream()
                .collect(Collectors.toMap(PayrollViews.Component::id, c -> c));
    }

    public PageResponse<PayrollViews.Component> listComponents(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PAY_COMPONENTS,
                PAY_COMPONENTS.COMPANY_ID.eq(companyId),
                query,
                COMPONENT_BINDING,
                PayrollConfigRepository::toComponent);
    }

    public Optional<PayrollViews.Component> lockComponent(UUID companyId, UUID id) {
        return dsl.selectFrom(PAY_COMPONENTS)
                .where(PAY_COMPONENTS.COMPANY_ID.eq(companyId))
                .and(PAY_COMPONENTS.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(PayrollConfigRepository::toComponent);
    }

    // ---------------------------------------------------------------------------- structures

    public UUID insertStructure(UUID companyId, String code, String name, UUID actor) {
        return dsl.insertInto(SALARY_STRUCTURES)
                .set(SALARY_STRUCTURES.COMPANY_ID, companyId)
                .set(SALARY_STRUCTURES.CODE, code)
                .set(SALARY_STRUCTURES.NAME, name)
                .set(SALARY_STRUCTURES.CREATED_BY, actor)
                .set(SALARY_STRUCTURES.UPDATED_BY, actor)
                .returning(SALARY_STRUCTURES.ID)
                .fetchSingle(SALARY_STRUCTURES.ID);
    }

    public boolean updateStructure(
            UUID companyId, UUID id, int expectedVersion, UUID actor, String name, boolean active) {
        return dsl.update(SALARY_STRUCTURES)
                        .set(SALARY_STRUCTURES.NAME, name)
                        .set(SALARY_STRUCTURES.IS_ACTIVE, active)
                        .set(SALARY_STRUCTURES.UPDATED_AT, OffsetDateTime.now())
                        .set(SALARY_STRUCTURES.UPDATED_BY, actor)
                        .set(SALARY_STRUCTURES.VERSION, expectedVersion + 1)
                        .where(SALARY_STRUCTURES.COMPANY_ID.eq(companyId))
                        .and(SALARY_STRUCTURES.ID.eq(id))
                        .and(SALARY_STRUCTURES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public void replaceStructureComponents(
            UUID companyId, UUID structureId, List<PayrollViews.StructureComponent> components) {
        dsl.deleteFrom(SALARY_STRUCTURE_COMPONENTS)
                .where(SALARY_STRUCTURE_COMPONENTS.COMPANY_ID.eq(companyId))
                .and(SALARY_STRUCTURE_COMPONENTS.STRUCTURE_ID.eq(structureId))
                .execute();
        for (PayrollViews.StructureComponent c : components) {
            dsl.insertInto(SALARY_STRUCTURE_COMPONENTS)
                    .set(SALARY_STRUCTURE_COMPONENTS.COMPANY_ID, companyId)
                    .set(SALARY_STRUCTURE_COMPONENTS.STRUCTURE_ID, structureId)
                    .set(SALARY_STRUCTURE_COMPONENTS.COMPONENT_ID, c.componentId())
                    .set(SALARY_STRUCTURE_COMPONENTS.RATE, c.rate())
                    .set(SALARY_STRUCTURE_COMPONENTS.AMOUNT, c.amount())
                    .execute();
        }
    }

    public Optional<PayrollViews.Structure> structure(UUID companyId, UUID id) {
        return dsl.selectFrom(SALARY_STRUCTURES)
                .where(SALARY_STRUCTURES.COMPANY_ID.eq(companyId))
                .and(SALARY_STRUCTURES.ID.eq(id))
                .fetchOptional(r -> toStructure(
                        r, structureComponents(companyId, List.of(id)).getOrDefault(id, List.of())));
    }

    public Optional<PayrollViews.Structure> lockStructure(UUID companyId, UUID id) {
        return dsl.selectFrom(SALARY_STRUCTURES)
                .where(SALARY_STRUCTURES.COMPANY_ID.eq(companyId))
                .and(SALARY_STRUCTURES.ID.eq(id))
                .forNoKeyUpdate()
                .fetchOptional(r -> toStructure(
                        r, structureComponents(companyId, List.of(id)).getOrDefault(id, List.of())));
    }

    /** Components per structure. */
    public Map<UUID, List<PayrollViews.StructureComponent>> structureComponents(
            UUID companyId, Collection<UUID> structureIds) {
        if (structureIds.isEmpty()) {
            return Map.of();
        }
        return dsl.select(
                        SALARY_STRUCTURE_COMPONENTS.STRUCTURE_ID,
                        SALARY_STRUCTURE_COMPONENTS.COMPONENT_ID,
                        PAY_COMPONENTS.CODE,
                        SALARY_STRUCTURE_COMPONENTS.RATE,
                        SALARY_STRUCTURE_COMPONENTS.AMOUNT)
                .from(SALARY_STRUCTURE_COMPONENTS)
                .join(PAY_COMPONENTS)
                .on(PAY_COMPONENTS
                        .COMPANY_ID
                        .eq(SALARY_STRUCTURE_COMPONENTS.COMPANY_ID)
                        .and(PAY_COMPONENTS.ID.eq(SALARY_STRUCTURE_COMPONENTS.COMPONENT_ID)))
                .where(SALARY_STRUCTURE_COMPONENTS.COMPANY_ID.eq(companyId))
                .and(SALARY_STRUCTURE_COMPONENTS.STRUCTURE_ID.in(structureIds))
                .orderBy(PAY_COMPONENTS.SEQUENCE, PAY_COMPONENTS.CODE)
                .fetchGroups(
                        r -> r.value1(),
                        r -> new PayrollViews.StructureComponent(r.value2(), r.value3(), r.value4(), r.value5()));
    }

    public PageResponse<PayrollViews.Structure> listStructures(UUID companyId, ListQuery query) {
        PageResponse<PayrollViews.Structure> page = paginator.fetch(
                dsl,
                SALARY_STRUCTURES,
                SALARY_STRUCTURES.COMPANY_ID.eq(companyId),
                query,
                STRUCTURE_BINDING,
                r -> toStructure(r, List.of()));
        Map<UUID, List<PayrollViews.StructureComponent>> components = structureComponents(
                companyId, page.data().stream().map(PayrollViews.Structure::id).toList());
        return page.map(s -> new PayrollViews.Structure(
                s.id(),
                s.code(),
                s.name(),
                s.active(),
                components.getOrDefault(s.id(), List.of()),
                s.createdAt(),
                s.updatedAt(),
                s.version()));
    }

    // ----------------------------------------------------------------------------- schedules

    public UUID insertSchedule(UUID companyId, PayrollCommands.Schedule c, UUID actor) {
        return dsl.insertInto(PAY_SCHEDULES)
                .set(PAY_SCHEDULES.COMPANY_ID, companyId)
                .set(PAY_SCHEDULES.CODE, c.code())
                .set(PAY_SCHEDULES.NAME, c.name())
                .set(PAY_SCHEDULES.FREQUENCY, c.frequency())
                .set(PAY_SCHEDULES.CURRENCY_CODE, c.currencyCode())
                .set(PAY_SCHEDULES.ANCHOR_DATE, c.anchorDate())
                .set(PAY_SCHEDULES.PAY_DAY_OFFSET, (short) c.payDayOffset())
                .set(PAY_SCHEDULES.CREATED_BY, actor)
                .set(PAY_SCHEDULES.UPDATED_BY, actor)
                .returning(PAY_SCHEDULES.ID)
                .fetchSingle(PAY_SCHEDULES.ID);
    }

    public boolean updateSchedule(
            UUID companyId, UUID id, int expectedVersion, UUID actor, String name, int payDayOffset, boolean active) {
        return dsl.update(PAY_SCHEDULES)
                        .set(PAY_SCHEDULES.NAME, name)
                        .set(PAY_SCHEDULES.PAY_DAY_OFFSET, (short) payDayOffset)
                        .set(PAY_SCHEDULES.IS_ACTIVE, active)
                        .set(PAY_SCHEDULES.UPDATED_AT, OffsetDateTime.now())
                        .set(PAY_SCHEDULES.UPDATED_BY, actor)
                        .set(PAY_SCHEDULES.VERSION, expectedVersion + 1)
                        .where(PAY_SCHEDULES.COMPANY_ID.eq(companyId))
                        .and(PAY_SCHEDULES.ID.eq(id))
                        .and(PAY_SCHEDULES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public Optional<PayrollViews.Schedule> schedule(UUID companyId, UUID id) {
        return dsl.selectFrom(PAY_SCHEDULES)
                .where(PAY_SCHEDULES.COMPANY_ID.eq(companyId))
                .and(PAY_SCHEDULES.ID.eq(id))
                .fetchOptional(PayrollConfigRepository::toSchedule);
    }

    public PageResponse<PayrollViews.Schedule> listSchedules(UUID companyId, ListQuery query) {
        return paginator.fetch(
                dsl,
                PAY_SCHEDULES,
                PAY_SCHEDULES.COMPANY_ID.eq(companyId),
                query,
                SCHEDULE_BINDING,
                PayrollConfigRepository::toSchedule);
    }

    static PayrollViews.Component toComponent(PayComponentsRecord r) {
        return new PayrollViews.Component(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getKind(),
                r.getCalculation(),
                r.getDefaultRate(),
                r.getDefaultAmount(),
                r.getIsTaxable(),
                r.getStatutoryRuleCode(),
                r.getSequence(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    private static PayrollViews.Structure toStructure(
            SalaryStructuresRecord r, List<PayrollViews.StructureComponent> components) {
        return new PayrollViews.Structure(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getIsActive(),
                components,
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    private static PayrollViews.Schedule toSchedule(PaySchedulesRecord r) {
        return new PayrollViews.Schedule(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getFrequency(),
                r.getCurrencyCode(),
                r.getAnchorDate(),
                r.getPayDayOffset(),
                r.getIsActive(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion());
    }

    static @Nullable String blank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
