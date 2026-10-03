package com.erp.procurement.application;

import com.erp.org.api.CompanyProfile;
import com.erp.partners.api.PartnersFacade;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.numbering.DocumentNumberService;
import com.erp.platform.numbering.FiscalYears;
import com.erp.platform.security.SegregationOfDuties;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import com.erp.procurement.domain.RequisitionStatus;
import com.erp.procurement.persistence.RequisitionRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Purchase requisitions (PRODUCT_SPEC.md §7): a branch asks for products; the requisition is
 * submitted (numbered), approved by someone else (G-17) with {@code procurement.requisition.approve}
 * or rejected, and its lines are then converted into purchase orders ({@link PurchaseOrderService}).
 */
@Service
public class RequisitionService {

    static final Set<String> PATCHABLE = Set.of("branchId", "departmentId", "neededBy", "notes", "lines");
    static final List<LinePatchReader.Member> LINE_MEMBERS = List.of(
            LinePatchReader.Member.uuid("variantId", true),
            LinePatchReader.Member.text("description", 300),
            LinePatchReader.Member.decimal("quantity", true),
            LinePatchReader.Member.uuid("uomId", true),
            LinePatchReader.Member.decimal("estimatedUnitPrice", false),
            LinePatchReader.Member.uuid("suggestedSupplierId", false));

    private final RequisitionRepository requisitions;
    private final DocumentPricing pricing;
    private final PartnersFacade partners;
    private final ProcurementContext context;
    private final DocumentNumberService numbering;
    private final AuditPort audit;

    RequisitionService(
            RequisitionRepository requisitions,
            DocumentPricing pricing,
            PartnersFacade partners,
            ProcurementContext context,
            DocumentNumberService numbering,
            AuditPort audit) {
        this.requisitions = requisitions;
        this.pricing = pricing;
        this.partners = partners;
        this.context = context;
        this.numbering = numbering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProcurementViews.Requisition> list(ListQuery query) {
        return requisitions.list(
                CurrentContext.requireCompany(), CurrentContext.require().branchScope(), query);
    }

    @Transactional(readOnly = true)
    public ProcurementViews.RequisitionDetail get(UUID id) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition requisition =
                visible(requisitions.find(companyId, id).orElseThrow(ApiException::notFound));
        return new ProcurementViews.RequisitionDetail(requisition, requisitions.lines(companyId, id));
    }

    @Transactional
    public ProcurementViews.RequisitionDetail create(ProcurementCommands.Requisition command) {
        UUID companyId = CurrentContext.requireCompany();
        List<FieldViolation> violations = new ArrayList<>();
        context.checkBranch(command.branchId(), command.departmentId(), "/branchId", violations);
        List<RequisitionRepository.NewLine> lines = lines(command.lines(), violations);
        UUID actor = context.actor();
        UUID id = requisitions.insert(
                companyId, command.branchId(), command.departmentId(), command.neededBy(), command.notes(), actor);
        requisitions.insertLines(companyId, id, lines, actor);
        audit.record(AuditEvent.builder("CREATE", "procurement")
                .entity("purchase_requisition", id, null)
                .detail("branchId", command.branchId())
                .detail("lines", lines.size())
                .build());
        return get(id);
    }

    /** Edits a draft; {@code lines} replaces all lines. */
    @Transactional
    public ProcurementViews.RequisitionDetail patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition current = lock(companyId, id, ifMatch, RequisitionStatus.Action.EDIT);
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var branch = patch.uuid("branchId", true);
        var department = patch.uuid("departmentId", false);
        var neededBy = patch.date("neededBy", false);
        var notes = patch.text("notes", false, 2000);
        patch.throwIfInvalid();
        UUID branchId = branch.orElse(current.branchId());
        UUID departmentId = department.orElse(current.departmentId());
        List<FieldViolation> violations = new ArrayList<>();
        context.checkBranch(branchId, departmentId, "/branchId", violations);
        List<RequisitionRepository.NewLine> lines = null;
        if (document.has("lines")) {
            lines = lines(readLines(document.get("lines")), violations);
        } else if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The requisition is invalid.", violations);
        }
        UUID actor = context.actor();
        if (lines != null) {
            requisitions.deleteLines(companyId, id);
            requisitions.insertLines(companyId, id, lines, actor);
        }
        if (!requisitions.updateDraft(
                companyId,
                id,
                current.version(),
                actor,
                branchId,
                departmentId,
                neededBy.orElse(current.neededBy()),
                notes.orElse(current.notes()))) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The requisition was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "procurement")
                .entity("purchase_requisition", id, current.number())
                .change("branchId", current.branchId(), branchId)
                .change("departmentId", current.departmentId(), departmentId)
                .change("neededBy", current.neededBy(), neededBy.orElse(current.neededBy()))
                .change("notes", current.notes(), notes.orElse(current.notes()))
                .detail("linesReplaced", lines != null)
                .build());
        return get(id);
    }

    @Transactional
    public void delete(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition current = lock(companyId, id, ifMatch, RequisitionStatus.Action.DELETE);
        if (!requisitions.delete(companyId, id, current.version())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The requisition was modified concurrently.");
        }
        audit.record(AuditEvent.builder("DELETE", "procurement")
                .entity("purchase_requisition", id, null)
                .build());
    }

    /** DRAFT → SUBMITTED; the requisition gets its number (G-6). */
    @Transactional
    public ProcurementViews.RequisitionDetail submit(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition current = lock(companyId, id, ifMatch, RequisitionStatus.Action.SUBMIT);
        CompanyProfile profile = context.profile();
        String number = current.number() != null
                ? current.number()
                : numbering.next(
                        companyId,
                        ProcurementConfiguration.REQUISITION,
                        FiscalYears.label(context.today(profile), profile.fiscalYearStartMonth()));
        transition(current, RequisitionStatus.Action.SUBMIT, number, true, false, null, null);
        return get(id);
    }

    /** SUBMITTED → APPROVED by someone other than the requester and the submitter (G-17). */
    @Transactional
    public ProcurementViews.RequisitionDetail approve(UUID id, @Nullable String ifMatch) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition current = lock(companyId, id, ifMatch, RequisitionStatus.Action.APPROVE);
        UUID actor = context.actor();
        SegregationOfDuties.requireDifferentUsers(actor, current.requestedBy(), "approve a requisition you requested");
        SegregationOfDuties.requireDifferentUsers(actor, current.submittedBy(), "approve a requisition you submitted");
        transition(current, RequisitionStatus.Action.APPROVE, null, false, true, null, null);
        return get(id);
    }

    @Transactional
    public ProcurementViews.RequisitionDetail reject(UUID id, @Nullable String ifMatch, String reason) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition current = lock(companyId, id, ifMatch, RequisitionStatus.Action.REJECT);
        transition(current, RequisitionStatus.Action.REJECT, null, false, false, reason, null);
        return get(id);
    }

    @Transactional
    public ProcurementViews.RequisitionDetail cancel(UUID id, @Nullable String ifMatch, @Nullable String reason) {
        UUID companyId = CurrentContext.requireCompany();
        ProcurementViews.Requisition current = lock(companyId, id, ifMatch, RequisitionStatus.Action.CANCEL);
        transition(current, RequisitionStatus.Action.CANCEL, null, false, false, null, reason == null ? "" : reason);
        return get(id);
    }

    // ------------------------------------------------------------------------------ helpers

    ProcurementViews.Requisition visible(ProcurementViews.Requisition requisition) {
        if (!context.canSeeBranch(requisition.branchId())) {
            throw ApiException.notFound();
        }
        return requisition;
    }

    private ProcurementViews.Requisition lock(
            UUID companyId, UUID id, @Nullable String ifMatch, RequisitionStatus.Action action) {
        ProcurementViews.Requisition current =
                visible(requisitions.lock(companyId, id).orElseThrow(ApiException::notFound));
        EntityTags.requireMatch(ifMatch, current.version());
        if (!RequisitionStatus.valueOf(current.status()).allows(action)) {
            throw new ApiException(
                    PlatformErrorCode.INVALID_STATE,
                    "A " + current.status() + " requisition does not allow "
                            + action.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return current;
    }

    private void transition(
            ProcurementViews.Requisition current,
            RequisitionStatus.Action action,
            @Nullable String number,
            boolean submitted,
            boolean approved,
            @Nullable String rejectionReason,
            @Nullable String cancelReason) {
        RequisitionStatus from = RequisitionStatus.valueOf(current.status());
        RequisitionStatus to = from.apply(action);
        if (!requisitions.transition(
                current.companyId(),
                current.id(),
                current.version(),
                context.actor(),
                to.name(),
                number,
                submitted,
                approved,
                rejectionReason,
                cancelReason == null || cancelReason.isBlank() ? null : cancelReason)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The requisition was modified concurrently.");
        }
        AuditEvent.Builder event = AuditEvent.builder("STATE_CHANGE", "procurement")
                .entity("purchase_requisition", current.id(), number != null ? number : current.number())
                .transition(from.name(), to.name());
        if (rejectionReason != null) {
            event.detail("reason", rejectionReason);
        }
        if (cancelReason != null && !cancelReason.isBlank()) {
            event.detail("reason", cancelReason);
        }
        audit.record(event.build());
    }

    static List<ProcurementCommands.RequisitionLine> readLines(JsonNode array) {
        return LinePatchReader.read(array, LINE_MEMBERS).stream()
                .map(v -> new ProcurementCommands.RequisitionLine(
                        v.uuid("variantId"),
                        v.text("description"),
                        v.decimal("quantity"),
                        v.uuid("uomId"),
                        v.decimal("estimatedUnitPrice"),
                        v.uuid("suggestedSupplierId")))
                .toList();
    }

    private List<RequisitionRepository.NewLine> lines(
            List<ProcurementCommands.RequisitionLine> lines, List<FieldViolation> violations) {
        if (lines.isEmpty() || lines.size() > DocumentPricing.MAX_LINES) {
            violations.add(FieldViolation.atPointer(
                    "/lines", "SIZE", "must contain between 1 and " + DocumentPricing.MAX_LINES + " lines"));
        }
        List<RequisitionRepository.NewLine> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ProcurementCommands.RequisitionLine line = lines.get(i);
            var variant = pricing.variant(i, line.variantId(), violations);
            BigDecimal base = variant == null
                    ? null
                    : pricing.quantityBase(i, line.variantId(), line.quantity(), line.uomId(), violations);
            BigDecimal price = line.estimatedUnitPrice();
            if (price != null && price.stripTrailingZeros().scale() > DocumentPricing.UNIT_PRICE_SCALE) {
                violations.add(FieldViolation.atPointer(
                        "/lines/" + i + "/estimatedUnitPrice", "TOO_PRECISE", "must have at most 6 decimal places"));
            }
            if (line.suggestedSupplierId() != null
                    && partners.supplier(line.suggestedSupplierId()).isEmpty()) {
                violations.add(FieldViolation.atPointer(
                        "/lines/" + i + "/suggestedSupplierId",
                        "UNKNOWN_SUPPLIER",
                        "is not a supplier of the company"));
            }
            if (variant != null && base != null) {
                result.add(new RequisitionRepository.NewLine(
                        i + 1,
                        line.variantId(),
                        line.description() != null ? line.description() : variant.name(),
                        line.quantity(),
                        line.uomId(),
                        base,
                        price,
                        line.suggestedSupplierId()));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validationFailed("The requisition is invalid.", violations);
        }
        return result;
    }
}
