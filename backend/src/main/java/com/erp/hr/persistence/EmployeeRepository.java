package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.EMPLOYEES;
import static com.erp.db.hr.Tables.EMPLOYMENT_ASSIGNMENTS;

import com.erp.db.hr.tables.records.EmployeesRecord;
import com.erp.hr.application.Address;
import com.erp.hr.application.EmployeeView;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrListings;
import com.erp.hr.domain.EmployeeStatus;
import com.erp.platform.jooq.KeysetPaginator;
import com.erp.platform.jooq.ListBinding;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Employees. With a restricted branch scope, an employee is visible only when their assignment
 * effective on the business date lies in one of the caller's branches (SECURITY.md §4.4).
 */
@Repository
public class EmployeeRepository {

    private static final ListBinding BINDING = ListBinding.builder(HrListings.EMPLOYEES)
            .field("employeeNumber", EMPLOYEES.EMPLOYEE_NUMBER)
            .field("lastName", EMPLOYEES.LAST_NAME)
            .field("hireDate", EMPLOYEES.HIRE_DATE)
            .field("createdAt", EMPLOYEES.CREATED_AT)
            .field("status", EMPLOYEES.STATUS)
            .field("workEmail", EMPLOYEES.WORK_EMAIL)
            .tiebreaker(EMPLOYEES.ID)
            .search(List.of(EMPLOYEES.EMPLOYEE_NUMBER, EMPLOYEES.FIRST_NAME, EMPLOYEES.LAST_NAME, EMPLOYEES.WORK_EMAIL))
            .build();

    private final DSLContext dsl;
    private final KeysetPaginator paginator;

    public EmployeeRepository(DSLContext dsl, KeysetPaginator paginator) {
        this.dsl = dsl;
        this.paginator = paginator;
    }

    public UUID insert(UUID companyId, HrCommands.Employee c, UUID actor) {
        return dsl.insertInto(EMPLOYEES)
                .set(EMPLOYEES.COMPANY_ID, companyId)
                .set(EMPLOYEES.EMPLOYEE_NUMBER, c.employeeNumber())
                .set(EMPLOYEES.FIRST_NAME, c.firstName())
                .set(EMPLOYEES.LAST_NAME, c.lastName())
                .set(EMPLOYEES.PREFERRED_NAME, c.preferredName())
                .set(EMPLOYEES.WORK_EMAIL, c.workEmail())
                .set(EMPLOYEES.HIRE_DATE, c.hireDate())
                .set(EMPLOYEES.STATUS, EmployeeStatus.ONBOARDING.name())
                .set(EMPLOYEES.CREATED_BY, actor)
                .set(EMPLOYEES.UPDATED_BY, actor)
                .returning(EMPLOYEES.ID)
                .fetchOne(EMPLOYEES.ID);
    }

    public Optional<EmployeeView> find(UUID companyId, UUID id, @Nullable Set<UUID> branchScope, LocalDate today) {
        return dsl.selectFrom(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.ID.eq(id))
                .and(visible(branchScope, today))
                .fetchOptional()
                .map(EmployeeRepository::toView);
    }

    /** Locks the employee ({@code FOR NO KEY UPDATE}): serializes assignment, headship and status changes. */
    public Optional<EmployeeView> lockForChange(
            UUID companyId, UUID id, @Nullable Set<UUID> branchScope, LocalDate today) {
        return dsl.selectFrom(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.ID.eq(id))
                .and(visible(branchScope, today))
                .forNoKeyUpdate()
                .fetchOptional()
                .map(EmployeeRepository::toView);
    }

    /** {@code FOR SHARE}, without the branch filter: for references such as a manager or department head. */
    public Optional<EmployeeView> lockForReference(UUID companyId, UUID id) {
        return dsl.selectFrom(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.ID.eq(id))
                .forShare()
                .fetchOptional()
                .map(EmployeeRepository::toView);
    }

    public PageResponse<EmployeeView> list(
            UUID companyId, @Nullable Set<UUID> branchScope, LocalDate today, ListQuery query) {
        return paginator.fetch(
                dsl,
                EMPLOYEES,
                EMPLOYEES.COMPANY_ID.eq(companyId).and(visible(branchScope, today)),
                query,
                BINDING,
                EmployeeRepository::toView);
    }

    public boolean updateProfile(UUID companyId, UUID id, int expectedVersion, UUID actor, HrCommands.Employee c) {
        return dsl.update(EMPLOYEES)
                        .set(EMPLOYEES.FIRST_NAME, c.firstName())
                        .set(EMPLOYEES.LAST_NAME, c.lastName())
                        .set(EMPLOYEES.PREFERRED_NAME, c.preferredName())
                        .set(EMPLOYEES.WORK_EMAIL, c.workEmail())
                        .set(EMPLOYEES.HIRE_DATE, c.hireDate())
                        .set(EMPLOYEES.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYEES.UPDATED_BY, actor)
                        .set(EMPLOYEES.VERSION, expectedVersion + 1)
                        .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                        .and(EMPLOYEES.ID.eq(id))
                        .and(EMPLOYEES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public boolean updateStatus(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            EmployeeStatus status,
            @Nullable LocalDate terminationDate,
            @Nullable String terminationReason) {
        return dsl.update(EMPLOYEES)
                        .set(EMPLOYEES.STATUS, status.name())
                        .set(EMPLOYEES.TERMINATION_DATE, terminationDate)
                        .set(EMPLOYEES.TERMINATION_REASON, terminationReason)
                        .set(EMPLOYEES.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYEES.UPDATED_BY, actor)
                        .set(EMPLOYEES.VERSION, expectedVersion + 1)
                        .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                        .and(EMPLOYEES.ID.eq(id))
                        .and(EMPLOYEES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    /** Whether the employee row is within the branch scope (for queries joining employees). */
    public static Condition visible(@Nullable Set<UUID> branchScope, LocalDate today) {
        if (branchScope == null) {
            return DSL.noCondition();
        }
        return DSL.exists(DSL.selectOne()
                .from(EMPLOYMENT_ASSIGNMENTS)
                .where(EMPLOYMENT_ASSIGNMENTS.COMPANY_ID.eq(EMPLOYEES.COMPANY_ID))
                .and(EMPLOYMENT_ASSIGNMENTS.EMPLOYEE_ID.eq(EMPLOYEES.ID))
                .and(EMPLOYMENT_ASSIGNMENTS.BRANCH_ID.in(branchScope))
                .and(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_FROM.le(today))
                .and(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO.isNull().or(EMPLOYMENT_ASSIGNMENTS.EFFECTIVE_TO.ge(today))));
    }

    /** The encrypted values of an employee (decrypted only by the reveal service). */
    public record Sensitive(byte @Nullable [] dateOfBirth, byte @Nullable [] nationalId) {}

    /** Profile, personal and (already encrypted) sensitive values in one optimistic update. */
    public boolean update(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            HrCommands.Employee c,
            @Nullable String personalEmail,
            @Nullable String phone,
            @Nullable Address address,
            byte @Nullable [] dateOfBirth,
            byte @Nullable [] nationalId,
            @Nullable String nationalIdLast4,
            @Nullable Integer keyVersion) {
        return dsl.update(EMPLOYEES)
                        .set(EMPLOYEES.FIRST_NAME, c.firstName())
                        .set(EMPLOYEES.LAST_NAME, c.lastName())
                        .set(EMPLOYEES.PREFERRED_NAME, c.preferredName())
                        .set(EMPLOYEES.WORK_EMAIL, c.workEmail())
                        .set(EMPLOYEES.HIRE_DATE, c.hireDate())
                        .set(EMPLOYEES.PERSONAL_EMAIL, personalEmail)
                        .set(EMPLOYEES.PHONE, phone)
                        .set(
                                EMPLOYEES.ADDRESS,
                                address == null ? null : JSONB.valueOf(JSON.writeValueAsString(address)))
                        .set(EMPLOYEES.DATE_OF_BIRTH_ENCRYPTED, dateOfBirth)
                        .set(EMPLOYEES.NATIONAL_ID_ENCRYPTED, nationalId)
                        .set(EMPLOYEES.NATIONAL_ID_LAST4, nationalIdLast4)
                        .set(EMPLOYEES.KEY_VERSION, keyVersion == null ? null : keyVersion.shortValue())
                        .set(EMPLOYEES.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYEES.UPDATED_BY, actor)
                        .set(EMPLOYEES.VERSION, expectedVersion + 1)
                        .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                        .and(EMPLOYEES.ID.eq(id))
                        .and(EMPLOYEES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public boolean updatePersonal(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            @Nullable String personalEmail,
            @Nullable String phone,
            @Nullable Address address) {
        return dsl.update(EMPLOYEES)
                        .set(EMPLOYEES.PERSONAL_EMAIL, personalEmail)
                        .set(EMPLOYEES.PHONE, phone)
                        .set(
                                EMPLOYEES.ADDRESS,
                                address == null ? null : JSONB.valueOf(JSON.writeValueAsString(address)))
                        .set(EMPLOYEES.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYEES.UPDATED_BY, actor)
                        .set(EMPLOYEES.VERSION, expectedVersion + 1)
                        .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                        .and(EMPLOYEES.ID.eq(id))
                        .and(EMPLOYEES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public boolean updateSensitive(
            UUID companyId,
            UUID id,
            int expectedVersion,
            UUID actor,
            byte @Nullable [] dateOfBirth,
            byte @Nullable [] nationalId,
            @Nullable String nationalIdLast4,
            @Nullable Integer keyVersion) {
        return dsl.update(EMPLOYEES)
                        .set(EMPLOYEES.DATE_OF_BIRTH_ENCRYPTED, dateOfBirth)
                        .set(EMPLOYEES.NATIONAL_ID_ENCRYPTED, nationalId)
                        .set(EMPLOYEES.NATIONAL_ID_LAST4, nationalIdLast4)
                        .set(EMPLOYEES.KEY_VERSION, keyVersion == null ? null : keyVersion.shortValue())
                        .set(EMPLOYEES.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYEES.UPDATED_BY, actor)
                        .set(EMPLOYEES.VERSION, expectedVersion + 1)
                        .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                        .and(EMPLOYEES.ID.eq(id))
                        .and(EMPLOYEES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    public @Nullable Integer keyVersion(UUID companyId, UUID id) {
        Short version = dsl.select(EMPLOYEES.KEY_VERSION)
                .from(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.ID.eq(id))
                .fetchSingle(EMPLOYEES.KEY_VERSION);
        return version == null ? null : version.intValue();
    }

    public Sensitive sensitive(UUID companyId, UUID id) {
        return dsl.select(EMPLOYEES.DATE_OF_BIRTH_ENCRYPTED, EMPLOYEES.NATIONAL_ID_ENCRYPTED)
                .from(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.ID.eq(id))
                .fetchSingle(r -> new Sensitive(r.value1(), r.value2()));
    }

    public boolean linkUser(UUID companyId, UUID id, int expectedVersion, UUID actor, @Nullable UUID userId) {
        return dsl.update(EMPLOYEES)
                        .set(EMPLOYEES.USER_ID, userId)
                        .set(EMPLOYEES.UPDATED_AT, OffsetDateTime.now())
                        .set(EMPLOYEES.UPDATED_BY, actor)
                        .set(EMPLOYEES.VERSION, expectedVersion + 1)
                        .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                        .and(EMPLOYEES.ID.eq(id))
                        .and(EMPLOYEES.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    /** The employee linked to the user in the company (self-service). */
    public Optional<EmployeeView> findByUser(UUID companyId, UUID userId) {
        return dsl.selectFrom(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.USER_ID.eq(userId))
                .fetchOptional(EmployeeRepository::toView);
    }

    public List<EmployeeView> findAll(UUID companyId, java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.ID.in(ids))
                .fetch(EmployeeRepository::toView);
    }

    /** Employees not terminated before {@code from} and hired by {@code to}. */
    public List<EmployeeView> employedBetween(UUID companyId, LocalDate from, LocalDate to) {
        return dsl.selectFrom(EMPLOYEES)
                .where(EMPLOYEES.COMPANY_ID.eq(companyId))
                .and(EMPLOYEES.HIRE_DATE.le(to))
                .and(EMPLOYEES.TERMINATION_DATE.isNull().or(EMPLOYEES.TERMINATION_DATE.ge(from)))
                .orderBy(EMPLOYEES.EMPLOYEE_NUMBER)
                .fetch(EmployeeRepository::toView);
    }

    static EmployeeView toView(EmployeesRecord r) {
        return new EmployeeView(
                r.getId(),
                r.getCompanyId(),
                r.getEmployeeNumber(),
                r.getFirstName(),
                r.getLastName(),
                r.getPreferredName(),
                r.getWorkEmail(),
                r.getHireDate(),
                r.getTerminationDate(),
                r.getTerminationReason(),
                EmployeeStatus.valueOf(r.getStatus()),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getVersion(),
                r.getUserId(),
                r.getPersonalEmail(),
                r.getPhone(),
                r.getAddress() == null ? null : JSON.readValue(r.getAddress().data(), Address.class),
                r.getNationalIdLast4(),
                r.getDateOfBirthEncrypted() != null);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
}
