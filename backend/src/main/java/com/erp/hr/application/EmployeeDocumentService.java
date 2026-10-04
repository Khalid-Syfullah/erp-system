package com.erp.hr.application;

import com.erp.hr.persistence.EmployeeDocumentRepository;
import com.erp.hr.persistence.EmployeeRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.files.FileService;
import com.erp.platform.files.StoredFile;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employee documents (contracts, identity documents, certificates …) stored as platform files
 * (ARCHITECTURE.md §8.6). The content is uploaded before the transaction that registers it; reading
 * one is audited because such documents carry personal data.
 */
@Service
public class EmployeeDocumentService {

    static final Set<String> TYPES = Set.of("CONTRACT", "IDENTITY", "CERTIFICATE", "WORK_PERMIT", "REVIEW", "OTHER");

    /** A document with its content. */
    public record Content(HrViews.Document document, byte[] bytes) {}

    private final EmployeeDocumentRepository documents;
    private final EmployeeRepository employees;
    private final FileService files;
    private final HrContext context;
    private final AuditPort audit;

    EmployeeDocumentService(
            EmployeeDocumentRepository documents,
            EmployeeRepository employees,
            FileService files,
            HrContext context,
            AuditPort audit) {
        this.documents = documents;
        this.employees = employees;
        this.files = files;
        this.context = context;
        this.audit = audit;
    }

    /** Validates the metadata before anything is uploaded. */
    public void validate(String documentType, String title) {
        if (!TYPES.contains(documentType)) {
            throw ApiException.validationFailed(
                    "The document is invalid.",
                    List.of(FieldViolation.atPointer("/documentType", "INVALID_VALUE", "must be one of " + TYPES)));
        }
        if (title.isBlank() || title.strip().length() > 200) {
            throw ApiException.validationFailed(
                    "The document is invalid.",
                    List.of(FieldViolation.atPointer("/title", "INVALID_VALUE", "must be 1 to 200 characters")));
        }
    }

    /** Registers the uploaded content as a document of the employee. */
    @Transactional
    public HrViews.Document attach(
            UUID employeeId,
            String documentType,
            String title,
            @Nullable LocalDate validUntil,
            FileService.Upload upload) {
        validate(documentType, title);
        UUID companyId = context.companyId();
        EmployeeView employee = employees
                .lockForChange(companyId, employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
        StoredFile file = files.register(upload, employeeId);
        UUID id = documents.insert(
                companyId,
                employeeId,
                documentType,
                title.strip(),
                file.id(),
                file.fileName(),
                file.contentType(),
                file.sizeBytes(),
                validUntil,
                context.actor());
        audit.record(AuditEvent.builder("CREATE", "hr")
                .entity("employee_document", id, employee.employeeNumber())
                .detail("documentType", documentType)
                .detail("fileName", file.fileName())
                .detail("sha256", file.sha256())
                .build());
        return documents.find(companyId, employeeId, id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<HrViews.Document> list(UUID employeeId) {
        employee(employeeId);
        return documents.list(context.companyId(), employeeId);
    }

    /** The document's metadata for a download; the download is audited. */
    @Transactional
    public HrViews.Document open(UUID employeeId, UUID documentId) {
        EmployeeView employee = employee(employeeId);
        HrViews.Document document =
                documents.find(context.companyId(), employeeId, documentId).orElseThrow(ApiException::notFound);
        audit.record(AuditEvent.builder("VIEW_SENSITIVE", "hr")
                .entity("employee_document", documentId, employee.employeeNumber())
                .detail("documentType", document.documentType())
                .build());
        return document;
    }

    /** The content of a document opened with {@link #open} (outside a transaction). */
    public Content content(HrViews.Document document) {
        return new Content(
                document,
                files.read(document.fileId())
                        .orElseThrow(ApiException::notFound)
                        .bytes());
    }

    @Transactional
    public void delete(UUID employeeId, UUID documentId) {
        UUID companyId = context.companyId();
        EmployeeView employee = employee(employeeId);
        HrViews.Document document =
                documents.find(companyId, employeeId, documentId).orElseThrow(ApiException::notFound);
        documents.delete(companyId, documentId);
        files.delete(document.fileId());
        audit.record(AuditEvent.builder("DELETE", "hr")
                .entity("employee_document", documentId, employee.employeeNumber())
                .detail("documentType", document.documentType())
                .detail("fileName", document.fileName())
                .build());
    }

    private EmployeeView employee(UUID employeeId) {
        return employees
                .find(context.companyId(), employeeId, CurrentContext.require().branchScope(), context.today())
                .orElseThrow(ApiException::notFound);
    }
}
