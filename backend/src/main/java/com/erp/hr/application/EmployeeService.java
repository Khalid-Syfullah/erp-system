package com.erp.hr.application;

import com.erp.auth.api.AuthFacade;
import com.erp.hr.HrPermissions;
import com.erp.hr.domain.EmployeeStatus;
import com.erp.hr.events.EmployeeHired;
import com.erp.hr.events.EmployeeTerminated;
import com.erp.hr.persistence.DepartmentHeadRepository;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.hr.persistence.EmploymentAssignmentRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.crypto.FieldEncryptor;
import com.erp.platform.events.DomainEvents;
import com.erp.platform.security.ReauthenticationGuard;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Core employee records and their lifecycle (PRODUCT_SPEC.md §10.1). A terminated employee is
 * read-only. Termination ends the employee's current assignment and department headships at the
 * termination date; it is refused while assignments or headships start after that date or other
 * employees still report to them afterwards. It cancels the employee's leave after that date,
 * disables the linked user through Auth and publishes {@code hr.employee.terminated} (Payroll ends
 * the compensation).
 *
 * <p>Personal data (personal email, phone, address) is part of the record. The date of birth and the
 * national ID are field-encrypted: setting them needs {@code hr.employee.read_sensitive}, responses
 * show only whether a date is stored and the ID's last four characters, and {@link #reveal} decrypts
 * them after a step-up with a {@code VIEW_SENSITIVE} audit record (SECURITY.md §7).
 *
 * <p>Branch-restricted users see employees whose current assignment is in their branches, and must
 * create new employees together with an initial assignment in one of them.
 */
@Service
public class EmployeeService {

    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final Set<String> PATCHABLE = Set.of(
            "firstName",
            "lastName",
            "preferredName",
            "workEmail",
            "hireDate",
            "personalEmail",
            "phone",
            "address",
            "dateOfBirth",
            "nationalId");
    static final Set<String> SELF_PATCHABLE = Set.of("preferredName", "personalEmail", "phone", "address");
    static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()./-]{3,30}$");
    private static final Set<String> ADDRESS_FIELDS =
            Set.of("line1", "line2", "city", "region", "postalCode", "countryCode");

    /** The decrypted sensitive values. */
    public record Revealed(
            UUID employeeId,
            @Nullable LocalDate dateOfBirth,
            @Nullable String nationalId) {}

    /** An employee with the assignment effective on the company's business date. */
    public record Detail(EmployeeView employee, @Nullable AssignmentView currentAssignment) {}

    private final EmployeeRepository employees;
    private final EmploymentAssignmentRepository assignmentRecords;
    private final EmploymentAssignmentService assignments;
    private final DepartmentHeadRepository heads;
    private final HrCalendar calendar;
    private final AuditPort audit;
    private final FieldEncryptor encryptor;
    private final AuthFacade auth;
    private final LeaveService leave;
    private final ApplicationEventPublisher events;
    private final HrContext context;

    EmployeeService(
            EmployeeRepository employees,
            EmploymentAssignmentRepository assignmentRecords,
            EmploymentAssignmentService assignments,
            DepartmentHeadRepository heads,
            HrCalendar calendar,
            AuditPort audit,
            FieldEncryptor encryptor,
            AuthFacade auth,
            LeaveService leave,
            ApplicationEventPublisher events,
            HrContext context) {
        this.employees = employees;
        this.assignmentRecords = assignmentRecords;
        this.assignments = assignments;
        this.heads = heads;
        this.calendar = calendar;
        this.audit = audit;
        this.encryptor = encryptor;
        this.auth = auth;
        this.leave = leave;
        this.events = events;
        this.context = context;
    }

    @Transactional(readOnly = true)
    public PageResponse<Detail> list(ListQuery query) {
        UUID companyId = CurrentContext.requireCompany();
        LocalDate today = calendar.today(companyId);
        PageResponse<EmployeeView> page =
                employees.list(companyId, CurrentContext.require().branchScope(), today, query);
        Map<UUID, AssignmentView> current = assignmentRecords.effectiveOn(
                companyId, page.data().stream().map(EmployeeView::id).toList(), today);
        return page.map(e -> new Detail(e, current.get(e.id())));
    }

    @Transactional(readOnly = true)
    public Detail get(UUID employeeId) {
        return get(employeeId, CurrentContext.require().branchScope());
    }

    /** The employee linked to the current user (self-service), or {@code 404 NOT_AN_EMPLOYEE}. */
    @Transactional(readOnly = true)
    public Detail own() {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView employee = employees
                .findByUser(companyId, context.actor())
                .orElseThrow(() -> new ApiException(
                        HrErrorCode.NOT_AN_EMPLOYEE, "You are not linked to an employee record in this company."));
        return get(employee.id(), null);
    }

    private Detail get(UUID employeeId, @Nullable Set<UUID> branchScope) {
        UUID companyId = CurrentContext.requireCompany();
        LocalDate today = calendar.today(companyId);
        EmployeeView employee =
                employees.find(companyId, employeeId, branchScope, today).orElseThrow(ApiException::notFound);
        return new Detail(
                employee,
                assignmentRecords
                        .effectiveOn(companyId, List.of(employeeId), today)
                        .get(employeeId));
    }

    @Transactional
    public Detail create(HrCommands.Employee command, HrCommands.@Nullable Assignment initialAssignment) {
        UUID companyId = CurrentContext.requireCompany();
        if (CurrentContext.require().branchScope() != null && initialAssignment == null) {
            throw new ApiException(
                    PlatformErrorCode.FORBIDDEN,
                    "Users restricted to some branches must create employees with an initial assignment in one of"
                            + " their branches.");
        }
        HrCommands.Employee normalized = normalize(command);
        if (normalized.workEmail() != null
                && !EMAIL.matcher(normalized.workEmail()).matches()) {
            throw ApiException.validationFailed(
                    "The employee is invalid.",
                    List.of(FieldViolation.atPointer("/workEmail", "INVALID_VALUE", "must be an email address")));
        }
        UUID id = employees.insert(
                companyId, normalized, CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("employee", id, normalized.employeeNumber())
                .detail("hireDate", normalized.hireDate().toString())
                .build());
        EmployeeView created = employees
                .lockForChange(companyId, id, null, calendar.today(companyId))
                .orElseThrow();
        if (initialAssignment != null) {
            assignments.createFor(created, initialAssignment);
        }
        events.publishEvent(new EmployeeHired(
                DomainEvents.metadata(EmployeeHired.TYPE, EmployeeHired.SCHEMA_VERSION, companyId, context.clock()),
                id,
                normalized.employeeNumber(),
                normalized.hireDate(),
                initialAssignment == null ? null : initialAssignment.branchId(),
                initialAssignment == null ? null : initialAssignment.departmentId()));
        return new Detail(
                created,
                assignmentRecords
                        .effectiveOn(companyId, List.of(id), calendar.today(companyId))
                        .get(id));
    }

    @Transactional
    public Detail patch(UUID employeeId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = lock(companyId, employeeId);
        return apply(current, ifMatch, document, PATCHABLE);
    }

    /** The employee changes their own preferred name and contact data (self-service, HR-4). */
    @Transactional
    public Detail patchOwn(UUID employeeId, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = employees
                .lockForChange(companyId, employeeId, null, calendar.today(companyId))
                .orElseThrow(ApiException::notFound);
        return apply(current, ifMatch, document, SELF_PATCHABLE);
    }

    private Detail apply(EmployeeView current, @Nullable String ifMatch, JsonNode document, Set<String> allowed) {
        UUID companyId = current.companyId();
        UUID employeeId = current.id();
        EntityTags.requireMatch(ifMatch, current.version());
        requireNotTerminated(current);
        MergePatch patch = MergePatch.of(document, allowed);
        var firstName = patch.text("firstName", true, 100);
        var lastName = patch.text("lastName", true, 100);
        var preferredName = patch.text("preferredName", false, 100);
        var workEmail = patch.text(
                "workEmail", false, 254, e -> EMAIL.matcher(e).matches() ? null : "must be an email address");
        var hireDate = patch.date("hireDate", true);
        var personalEmail = patch.text(
                "personalEmail", false, 254, e -> EMAIL.matcher(e).matches() ? null : "must be an email address");
        var phone = patch.text("phone", false, 30, p -> PHONE.matcher(p).matches() ? null : "must be a phone number");
        var nationalId = patch.text(
                "nationalId",
                false,
                50,
                v -> v.matches("^[A-Za-z0-9 ./-]{4,50}$") ? null : "must be 4 to 50 characters");
        var dateOfBirth = patch.date("dateOfBirth", false);
        Address address = current.address();
        boolean addressChanged = false;
        if (document.has("address")) {
            address = parseAddress(document.get("address"), patch);
            addressChanged = !Objects.equals(address, current.address());
        }
        patch.throwIfInvalid();
        boolean sensitive = nationalId.present() || dateOfBirth.present();
        if (sensitive) {
            context.require(HrPermissions.EMPLOYEE_READ_SENSITIVE, "Setting the date of birth or national ID");
        }
        if (dateOfBirth.value() != null
                && (dateOfBirth.value().isAfter(calendar.today(companyId))
                        || dateOfBirth.value().isBefore(LocalDate.of(1900, 1, 1)))) {
            throw ApiException.validationFailed(
                    "The employee is invalid.",
                    List.of(FieldViolation.atPointer("/dateOfBirth", "INVALID_VALUE", "must be a past date")));
        }

        LocalDate newHire = hireDate.orElse(current.hireDate());
        if (!newHire.equals(current.hireDate())) {
            Optional<LocalDate> firstAssignment = assignmentRecords.forEmployee(companyId, employeeId).stream()
                    .map(AssignmentView::effectiveFrom)
                    .min(LocalDate::compareTo);
            Optional<LocalDate> firstHeadship = heads.earliestStart(companyId, employeeId);
            if (firstAssignment.filter(newHire::isAfter).isPresent()
                    || firstHeadship.filter(newHire::isAfter).isPresent()) {
                throw ApiException.validationFailed(
                        "The employee is invalid.",
                        List.of(FieldViolation.atPointer(
                                "/hireDate",
                                "AFTER_FIRST_ASSIGNMENT",
                                "must not be after the start of the employee's first assignment or headship")));
            }
        }
        HrCommands.Employee next = normalize(new HrCommands.Employee(
                current.employeeNumber(),
                firstName.orElse(current.firstName()),
                lastName.orElse(current.lastName()),
                preferredName.orElse(current.preferredName()),
                workEmail.orElse(current.workEmail()),
                newHire));
        String nextPersonalEmail = blankToNull(personalEmail.orElse(current.personalEmail()));
        if (nextPersonalEmail != null) {
            nextPersonalEmail = nextPersonalEmail.toLowerCase(java.util.Locale.ROOT);
        }
        String nextPhone = blankToNull(phone.orElse(current.phone()));

        // Sensitive values: both are re-encrypted with the active key whenever either changes.
        EmployeeRepository.Sensitive stored = employees.sensitive(companyId, employeeId);
        byte[] dob = stored.dateOfBirth();
        byte[] nid = stored.nationalId();
        String last4 = current.nationalIdLast4();
        Integer keyVersion = employees.keyVersion(companyId, employeeId);
        if (sensitive) {
            LocalDate dobValue = dateOfBirth.present()
                    ? dateOfBirth.value()
                    : dob == null ? null : LocalDate.parse(decrypt(dob, "date_of_birth", employeeId));
            String nidValue = nationalId.present()
                    ? blankToNull(nationalId.value())
                    : nid == null ? null : decrypt(nid, "national_id", employeeId);
            dob = dobValue == null ? null : encrypt(dobValue.toString(), "date_of_birth", employeeId);
            nid = nidValue == null ? null : encrypt(nidValue, "national_id", employeeId);
            last4 = nidValue == null ? null : nidValue.substring(Math.max(0, nidValue.length() - 4));
            keyVersion = dob == null && nid == null ? null : encryptor.activeKeyVersion();
        }
        if (!employees.update(
                companyId,
                employeeId,
                current.version(),
                CurrentContext.requireActor().userId(),
                next,
                nextPersonalEmail,
                nextPhone,
                address,
                dob,
                nid,
                last4,
                keyVersion)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The employee was modified concurrently.");
        }
        Detail after = get(employeeId, null);
        EmployeeView e = after.employee();
        AuditEvent.Builder event = AuditEvent.builder("UPDATE", "hr")
                .entity("employee", employeeId, e.employeeNumber())
                .change("firstName", current.firstName(), e.firstName())
                .change("lastName", current.lastName(), e.lastName())
                .change("preferredName", current.preferredName(), e.preferredName())
                .change("workEmail", current.workEmail(), e.workEmail())
                .change("hireDate", current.hireDate().toString(), e.hireDate().toString())
                .change("personalEmail", current.personalEmail(), e.personalEmail())
                .change("phone", current.phone(), e.phone());
        if (addressChanged) {
            event.detail("addressChanged", true);
        }
        if (dateOfBirth.present()) {
            event.redactedChange("dateOfBirth");
        }
        if (nationalId.present()) {
            event.redactedChange("nationalId");
        }
        audit.record(event.build());
        return after;
    }

    /** Decrypts the sensitive values after a recent password confirmation; audited (SECURITY.md §7). */
    @Transactional
    public Revealed reveal(UUID employeeId) {
        ReauthenticationGuard.require(
                CurrentContext.requireActor(), context.clock().instant());
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView employee = get(employeeId).employee();
        EmployeeRepository.Sensitive stored = employees.sensitive(companyId, employeeId);
        LocalDate dob = stored.dateOfBirth() == null
                ? null
                : LocalDate.parse(decrypt(stored.dateOfBirth(), "date_of_birth", employeeId));
        String nid = stored.nationalId() == null ? null : decrypt(stored.nationalId(), "national_id", employeeId);
        audit.record(AuditEvent.builder("VIEW_SENSITIVE", "hr")
                .entity("employee", employeeId, employee.employeeNumber())
                .detail("fields", "dateOfBirth,nationalId")
                .build());
        return new Revealed(employeeId, dob, nid);
    }

    /** Links a user of the company to the employee (self-service), or unlinks with {@code userId} null. */
    @Transactional
    public Detail linkUser(UUID employeeId, @Nullable String ifMatch, @Nullable UUID userId) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = lock(companyId, employeeId);
        EntityTags.requireMatch(ifMatch, current.version());
        requireNotTerminated(current);
        if (userId != null) {
            if (auth.linkableUser(userId, companyId).isEmpty()) {
                throw ApiException.validationFailed(
                        "The user cannot be linked.",
                        List.of(FieldViolation.atPointer(
                                "/userId",
                                "UNKNOWN_USER",
                                "must be an active person with a role assignment in the company")));
            }
            employees
                    .findByUser(companyId, userId)
                    .filter(other -> !other.id().equals(employeeId))
                    .ifPresent(other -> {
                        throw new ApiException(
                                HrErrorCode.USER_ALREADY_LINKED,
                                "The user is linked to employee " + other.employeeNumber() + ".");
                    });
        }
        if (!employees.linkUser(companyId, employeeId, current.version(), context.actor(), userId)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The employee was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "hr")
                .entity("employee", employeeId, current.employeeNumber())
                .change("userId", current.userId(), userId)
                .build());
        return get(employeeId, null);
    }

    /** {@code ONBOARDING → ACTIVE} and {@code ON_LEAVE → ACTIVE}. */
    @Transactional
    public Detail activate(UUID employeeId, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = lock(companyId, employeeId);
        EntityTags.requireMatch(ifMatch, current.version());
        transition(current, EmployeeStatus.ACTIVE, null, null);
        return get(employeeId);
    }

    @Transactional
    public Detail terminate(
            UUID employeeId, @Nullable String ifMatch, LocalDate terminationDate, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        EmployeeView current = lock(companyId, employeeId);
        EntityTags.requireMatch(ifMatch, current.version());
        if (!current.status().canTransitionTo(EmployeeStatus.TERMINATED)) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The employee is already terminated.");
        }
        if (terminationDate.isBefore(current.hireDate())) {
            throw ApiException.validationFailed(
                    "The termination is invalid.",
                    List.of(FieldViolation.atPointer(
                            "/terminationDate", "BEFORE_HIRE", "must not be before the hire date")));
        }
        List<AssignmentView> all = assignmentRecords.forEmployee(companyId, employeeId);
        List<DepartmentHeadView> headships = heads.forEmployeeFrom(companyId, employeeId, terminationDate);
        if (all.stream().anyMatch(a -> a.effectiveFrom().isAfter(terminationDate))
                || headships.stream().anyMatch(h -> h.effectiveFrom().isAfter(terminationDate))) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "Assignments or department headships start after the termination date; delete or end them first.");
        }
        int reports = assignmentRecords.countReportsAfter(companyId, employeeId, terminationDate);
        if (reports > 0) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    reports + " assignment(s) still report to this employee after the termination date;"
                            + " change their manager first.");
        }
        for (AssignmentView a : all) {
            if (a.effectiveTo() == null || a.effectiveTo().isAfter(terminationDate)) {
                assignments.endAt(current, a, terminationDate);
            }
        }
        UUID actor = CurrentContext.requireActor().userId();
        for (DepartmentHeadView h : headships) {
            if (h.effectiveTo() == null || h.effectiveTo().isAfter(terminationDate)) {
                if (!heads.updateEnd(companyId, h.id(), h.version(), actor, terminationDate)) {
                    throw new ApiException(
                            PlatformErrorCode.VERSION_CONFLICT, "A department headship was modified concurrently.");
                }
                audit.record(AuditEvent.builder("UPDATE", "hr")
                        .entity("department_head", h.id(), current.employeeNumber())
                        .change("effectiveTo", Objects.toString(h.effectiveTo(), null), terminationDate.toString())
                        .detail("reason", "TERMINATION")
                        .build());
            }
        }
        leave.cancelAfterTermination(current, terminationDate);
        EmployeeView refreshed = lock(companyId, employeeId);
        transition(refreshed, EmployeeStatus.TERMINATED, terminationDate, reason);
        if (current.userId() != null) {
            auth.deactivateForTermination(current.userId());
        }
        events.publishEvent(new EmployeeTerminated(
                DomainEvents.metadata(
                        EmployeeTerminated.TYPE, EmployeeTerminated.SCHEMA_VERSION, companyId, context.clock()),
                employeeId,
                current.employeeNumber(),
                terminationDate,
                reason));
        return get(employeeId);
    }

    private void transition(
            EmployeeView current, EmployeeStatus target, @Nullable LocalDate terminationDate, @Nullable String reason) {
        if (!current.status().canTransitionTo(target)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "An employee in status " + current.status() + " cannot become " + target + ".");
        }
        if (!employees.updateStatus(
                current.companyId(),
                current.id(),
                current.version(),
                CurrentContext.requireActor().userId(),
                target,
                terminationDate,
                reason)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The employee was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "hr")
                .entity("employee", current.id(), current.employeeNumber())
                .transition(current.status().name(), target.name());
        if (terminationDate != null) {
            event.detail("terminationDate", terminationDate.toString());
        }
        audit.record(event.build());
    }

    private EmployeeView lock(UUID companyId, UUID employeeId) {
        return employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), calendar.today(companyId))
                .orElseThrow(ApiException::notFound);
    }

    private static void requireNotTerminated(EmployeeView employee) {
        if (employee.status().isTerminal()) {
            throw new ApiException(PlatformErrorCode.INVALID_STATE, "The employee is terminated and read-only.");
        }
    }

    private @Nullable Address parseAddress(@Nullable JsonNode node, MergePatch patch) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            patch.reject("address", "INVALID_VALUE", "must be an object");
            return null;
        }
        for (String name : node.propertyNames()) {
            if (!ADDRESS_FIELDS.contains(name)) {
                patch.reject("address/" + name, "UNKNOWN_PROPERTY", "is not a recognized property");
            }
        }
        Address address = new Address(
                field(node, "line1", 200, patch),
                field(node, "line2", 200, patch),
                field(node, "city", 100, patch),
                field(node, "region", 100, patch),
                field(node, "postalCode", 20, patch),
                field(node, "countryCode", 2, patch));
        if (address.countryCode() != null && !address.countryCode().matches("^[A-Z]{2}$")) {
            patch.reject("address/countryCode", "INVALID_VALUE", "must be an ISO 3166 code");
        }
        return address;
    }

    private static @Nullable String field(JsonNode node, String name, int maxLength, MergePatch patch) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            patch.reject("address/" + name, "INVALID_VALUE", "must be a string");
            return null;
        }
        String text = value.asString().strip();
        if (text.length() > maxLength) {
            patch.reject("address/" + name, "INVALID_VALUE", "must be at most " + maxLength + " characters");
        }
        return text.isEmpty() ? null : text;
    }

    private byte[] encrypt(String value, String column, UUID employeeId) {
        return encryptor.encrypt(value.getBytes(StandardCharsets.UTF_8), associatedData(column, employeeId));
    }

    private String decrypt(byte[] value, String column, UUID employeeId) {
        return new String(encryptor.decrypt(value, associatedData(column, employeeId)), StandardCharsets.UTF_8);
    }

    private static String associatedData(String column, UUID employeeId) {
        return "hr.employees." + column + ":" + employeeId;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static HrCommands.Employee normalize(HrCommands.Employee c) {
        return new HrCommands.Employee(
                c.employeeNumber(),
                c.firstName().strip(),
                c.lastName().strip(),
                c.preferredName() == null || c.preferredName().isBlank()
                        ? null
                        : c.preferredName().strip(),
                c.workEmail() == null || c.workEmail().isBlank()
                        ? null
                        : c.workEmail().strip().toLowerCase(java.util.Locale.ROOT),
                c.hireDate());
    }
}
