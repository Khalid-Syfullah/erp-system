package com.erp.auth.persistence;

import static com.erp.db.auth.Tables.PERMISSIONS;
import static com.erp.db.auth.Tables.ROLES;
import static com.erp.db.auth.Tables.ROLE_ASSIGNMENTS;
import static com.erp.db.auth.Tables.ROLE_ASSIGNMENT_BRANCHES;
import static com.erp.db.auth.Tables.ROLE_PERMISSIONS;
import static com.erp.db.auth.Tables.USERS;

import com.erp.auth.application.AssignmentInfo;
import com.erp.auth.application.CompanyGrant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Role assignments and the effective-access queries behind authorization (SECURITY.md §4.1). An
 * assignment is valid on a day when {@code valid_from <= day <= valid_to} (open ends allowed).
 */
@Repository
public class AssignmentRepository {

    private final DSLContext dsl;

    public AssignmentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID insert(
            UUID userId,
            UUID roleId,
            UUID companyId,
            Set<UUID> branchIds,
            @Nullable LocalDate validFrom,
            @Nullable LocalDate validTo,
            UUID actor,
            OffsetDateTime now) {
        UUID id = dsl.insertInto(ROLE_ASSIGNMENTS)
                .set(ROLE_ASSIGNMENTS.USER_ID, userId)
                .set(ROLE_ASSIGNMENTS.ROLE_ID, roleId)
                .set(ROLE_ASSIGNMENTS.COMPANY_ID, companyId)
                .set(ROLE_ASSIGNMENTS.VALID_FROM, validFrom)
                .set(ROLE_ASSIGNMENTS.VALID_TO, validTo)
                .set(ROLE_ASSIGNMENTS.CREATED_AT, now)
                .set(ROLE_ASSIGNMENTS.UPDATED_AT, now)
                .set(ROLE_ASSIGNMENTS.CREATED_BY, actor)
                .set(ROLE_ASSIGNMENTS.UPDATED_BY, actor)
                .returning(ROLE_ASSIGNMENTS.ID)
                .fetchOne(ROLE_ASSIGNMENTS.ID);
        if (!branchIds.isEmpty()) {
            var insert = dsl.insertInto(
                    ROLE_ASSIGNMENT_BRANCHES,
                    ROLE_ASSIGNMENT_BRANCHES.ROLE_ASSIGNMENT_ID,
                    ROLE_ASSIGNMENT_BRANCHES.COMPANY_ID,
                    ROLE_ASSIGNMENT_BRANCHES.BRANCH_ID);
            for (UUID branchId : branchIds) {
                insert = insert.values(id, companyId, branchId);
            }
            insert.execute();
        }
        return id;
    }

    public Optional<AssignmentInfo> find(UUID assignmentId) {
        return fetchInfos(ROLE_ASSIGNMENTS.ID.eq(assignmentId)).stream().findFirst();
    }

    public List<AssignmentInfo> listForUser(UUID userId) {
        return fetchInfos(ROLE_ASSIGNMENTS.USER_ID.eq(userId));
    }

    public List<AssignmentInfo> listForCompany(UUID companyId) {
        return fetchInfos(ROLE_ASSIGNMENTS.COMPANY_ID.eq(companyId));
    }

    public int delete(UUID assignmentId) {
        return dsl.deleteFrom(ROLE_ASSIGNMENTS)
                .where(ROLE_ASSIGNMENTS.ID.eq(assignmentId))
                .execute();
    }

    public boolean hasAnyAssignment(UUID userId) {
        return dsl.fetchExists(ROLE_ASSIGNMENTS, ROLE_ASSIGNMENTS.USER_ID.eq(userId));
    }

    /** The user's effective access to the company on {@code day}, or empty without a valid assignment. */
    public Optional<CompanyGrant> grant(UUID userId, UUID companyId, LocalDate day) {
        List<UUID> assignmentIds = dsl.select(ROLE_ASSIGNMENTS.ID)
                .from(ROLE_ASSIGNMENTS)
                .where(ROLE_ASSIGNMENTS.USER_ID.eq(userId))
                .and(ROLE_ASSIGNMENTS.COMPANY_ID.eq(companyId))
                .and(validOn(day))
                .fetch(ROLE_ASSIGNMENTS.ID);
        if (assignmentIds.isEmpty()) {
            return Optional.empty();
        }
        Set<String> permissions = new HashSet<>(dsl.selectDistinct(ROLE_PERMISSIONS.PERMISSION_CODE)
                .from(ROLE_ASSIGNMENTS)
                .join(ROLE_PERMISSIONS)
                .on(ROLE_PERMISSIONS.ROLE_ID.eq(ROLE_ASSIGNMENTS.ROLE_ID))
                .join(PERMISSIONS)
                .on(PERMISSIONS.CODE.eq(ROLE_PERMISSIONS.PERMISSION_CODE))
                .where(ROLE_ASSIGNMENTS.ID.in(assignmentIds))
                .and(PERMISSIONS.DEPRECATED_AT.isNull())
                .fetch(ROLE_PERMISSIONS.PERMISSION_CODE));
        Map<UUID, Set<UUID>> branches = new HashMap<>();
        dsl.selectFrom(ROLE_ASSIGNMENT_BRANCHES)
                .where(ROLE_ASSIGNMENT_BRANCHES.ROLE_ASSIGNMENT_ID.in(assignmentIds))
                .fetch()
                .forEach(r -> branches.computeIfAbsent(r.getRoleAssignmentId(), k -> new HashSet<>())
                        .add(r.getBranchId()));
        boolean unrestricted = assignmentIds.stream().anyMatch(id -> !branches.containsKey(id));
        Set<UUID> scope = null;
        if (!unrestricted) {
            scope = new HashSet<>();
            branches.values().forEach(scope::addAll);
        }
        return Optional.of(new CompanyGrant(companyId, permissions, scope));
    }

    public Set<UUID> companiesWithValidAssignments(UUID userId, LocalDate day) {
        return new HashSet<>(dsl.selectDistinct(ROLE_ASSIGNMENTS.COMPANY_ID)
                .from(ROLE_ASSIGNMENTS)
                .where(ROLE_ASSIGNMENTS.USER_ID.eq(userId))
                .and(validOn(day))
                .fetch(ROLE_ASSIGNMENTS.COMPANY_ID));
    }

    /** Whether any valid assignment requires MFA: a role flagged requires_mfa or holding a sensitive permission. */
    public boolean requiresMfa(UUID userId, LocalDate day) {
        Condition flagged = ROLES.REQUIRES_MFA
                .isTrue()
                .or(org.jooq.impl.DSL.exists(dsl.selectOne()
                        .from(ROLE_PERMISSIONS)
                        .join(PERMISSIONS)
                        .on(PERMISSIONS.CODE.eq(ROLE_PERMISSIONS.PERMISSION_CODE))
                        .where(ROLE_PERMISSIONS.ROLE_ID.eq(ROLES.ID))
                        .and(PERMISSIONS.IS_SENSITIVE.isTrue())));
        return dsl.fetchExists(dsl.selectOne()
                .from(ROLE_ASSIGNMENTS)
                .join(ROLES)
                .on(ROLES.ID.eq(ROLE_ASSIGNMENTS.ROLE_ID))
                .where(ROLE_ASSIGNMENTS.USER_ID.eq(userId))
                .and(validOn(day))
                .and(flagged));
    }

    /** Users holding a valid assignment of the role (to invalidate their cached permissions). */
    public Set<UUID> usersWithRole(UUID roleId) {
        return new HashSet<>(dsl.selectDistinct(ROLE_ASSIGNMENTS.USER_ID)
                .from(ROLE_ASSIGNMENTS)
                .where(ROLE_ASSIGNMENTS.ROLE_ID.eq(roleId))
                .fetch(ROLE_ASSIGNMENTS.USER_ID));
    }

    private static Condition validOn(LocalDate day) {
        return ROLE_ASSIGNMENTS
                .VALID_FROM
                .isNull()
                .or(ROLE_ASSIGNMENTS.VALID_FROM.le(day))
                .and(ROLE_ASSIGNMENTS.VALID_TO.isNull().or(ROLE_ASSIGNMENTS.VALID_TO.ge(day)));
    }

    private List<AssignmentInfo> fetchInfos(Condition condition) {
        var rows = dsl.select(
                        ROLE_ASSIGNMENTS.ID,
                        ROLE_ASSIGNMENTS.USER_ID,
                        USERS.EMAIL,
                        USERS.DISPLAY_NAME,
                        ROLE_ASSIGNMENTS.ROLE_ID,
                        ROLES.CODE,
                        ROLE_ASSIGNMENTS.COMPANY_ID,
                        ROLE_ASSIGNMENTS.VALID_FROM,
                        ROLE_ASSIGNMENTS.VALID_TO,
                        ROLE_ASSIGNMENTS.CREATED_AT)
                .from(ROLE_ASSIGNMENTS)
                .join(USERS)
                .on(USERS.ID.eq(ROLE_ASSIGNMENTS.USER_ID))
                .join(ROLES)
                .on(ROLES.ID.eq(ROLE_ASSIGNMENTS.ROLE_ID))
                .where(condition)
                .orderBy(USERS.EMAIL, ROLES.CODE, ROLE_ASSIGNMENTS.ID)
                .fetch();
        Map<UUID, Set<UUID>> branches = branchesOf(rows.map(r -> r.get(ROLE_ASSIGNMENTS.ID)));
        return rows.map(r -> toInfo(r, branches.getOrDefault(r.get(ROLE_ASSIGNMENTS.ID), Set.of())));
    }

    private Map<UUID, Set<UUID>> branchesOf(Collection<UUID> assignmentIds) {
        Map<UUID, Set<UUID>> branches = new HashMap<>();
        if (!assignmentIds.isEmpty()) {
            dsl.selectFrom(ROLE_ASSIGNMENT_BRANCHES)
                    .where(ROLE_ASSIGNMENT_BRANCHES.ROLE_ASSIGNMENT_ID.in(assignmentIds))
                    .fetch()
                    .forEach(r -> branches.computeIfAbsent(r.getRoleAssignmentId(), k -> new HashSet<>())
                            .add(r.getBranchId()));
        }
        return branches;
    }

    private static AssignmentInfo toInfo(Record r, Set<UUID> branches) {
        return new AssignmentInfo(
                r.get(ROLE_ASSIGNMENTS.ID),
                r.get(ROLE_ASSIGNMENTS.USER_ID),
                r.get(USERS.EMAIL),
                r.get(USERS.DISPLAY_NAME),
                r.get(ROLE_ASSIGNMENTS.ROLE_ID),
                r.get(ROLES.CODE),
                r.get(ROLE_ASSIGNMENTS.COMPANY_ID),
                Set.copyOf(branches),
                r.get(ROLE_ASSIGNMENTS.VALID_FROM),
                r.get(ROLE_ASSIGNMENTS.VALID_TO),
                r.get(ROLE_ASSIGNMENTS.CREATED_AT));
    }
}
