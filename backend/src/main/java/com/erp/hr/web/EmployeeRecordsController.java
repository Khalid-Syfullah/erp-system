package com.erp.hr.web;

import com.erp.hr.HrPermissions;
import com.erp.hr.application.EmployeeBankAccountService;
import com.erp.hr.application.EmployeeDocumentService;
import com.erp.hr.application.HrCommands;
import com.erp.hr.application.HrViews;
import com.erp.platform.files.FileService;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.FieldViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Employee bank accounts and documents (API.md §17.9). */
@RestController
class EmployeeRecordsController {

    private static final String E = ApiPaths.V1 + "/companies/{companyId}/employees/{employeeId}";

    private final EmployeeBankAccountService bankAccounts;
    private final EmployeeDocumentService documents;
    private final FileService files;

    EmployeeRecordsController(
            EmployeeBankAccountService bankAccounts, EmployeeDocumentService documents, FileService files) {
        this.bankAccounts = bankAccounts;
        this.documents = documents;
        this.files = files;
    }

    record BankAccountRequest(
            @NotBlank @Size(max = 100) String bankName,
            @NotBlank @Size(max = 150) String accountHolder,
            @NotBlank @Size(max = 64) String accountNumber,
            @Size(max = 64) @Nullable String iban,

            @Pattern(regexp = "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$") @Nullable String swiftBic,

            @Nullable Boolean primary) {}

    record BankAccountResponse(
            UUID id,
            String bankName,
            String accountHolder,
            String accountNumberMasked,
            @Nullable String swiftBic,
            boolean hasIban,
            boolean primary,
            int version) {
        static BankAccountResponse from(HrViews.BankAccount a) {
            return new BankAccountResponse(
                    a.id(),
                    a.bankName(),
                    a.accountHolder(),
                    "****" + a.last4(),
                    a.swiftBic(),
                    a.hasIban(),
                    a.primary(),
                    a.version());
        }
    }

    record RevealedBankAccount(
            UUID id, String accountNumber, @Nullable String iban) {}

    record ListResponse<T>(List<T> data) {}

    // ------------------------------------------------------------------------- bank accounts

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE_BANK)
    @GetMapping(E + "/bank-accounts")
    ListResponse<BankAccountResponse> bankAccounts(@PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return new ListResponse<>(bankAccounts.list(employeeId).stream()
                .map(BankAccountResponse::from)
                .toList());
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE_BANK)
    @PostMapping(E + "/bank-accounts")
    ResponseEntity<BankAccountResponse> createBankAccount(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @Valid @RequestBody BankAccountRequest request) {
        HrViews.BankAccount created = bankAccounts.create(
                employeeId,
                new HrCommands.BankAccount(
                        request.bankName(),
                        request.accountHolder(),
                        request.accountNumber(),
                        request.iban(),
                        request.swiftBic(),
                        Boolean.TRUE.equals(request.primary())));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/employees/" + employeeId
                        + "/bank-accounts/" + created.id()))
                .body(BankAccountResponse.from(created));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE_BANK)
    @PostMapping(E + "/bank-accounts/{accountId}/make-primary")
    BankAccountResponse makePrimary(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @PathVariable UUID accountId) {
        return BankAccountResponse.from(bankAccounts.makePrimary(employeeId, accountId));
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE_BANK)
    @DeleteMapping(E + "/bank-accounts/{accountId}")
    ResponseEntity<Void> deleteBankAccount(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @PathVariable UUID accountId) {
        bankAccounts.delete(employeeId, accountId);
        return ResponseEntity.noContent().build();
    }

    /** Audit-logged reveal of the full numbers; requires a password confirmation in the last 5 minutes. */
    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE_BANK)
    @PostMapping(E + "/bank-accounts/{accountId}/reveal")
    RevealedBankAccount revealBankAccount(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @PathVariable UUID accountId) {
        var revealed = bankAccounts.reveal(employeeId, accountId);
        return new RevealedBankAccount(revealed.id(), revealed.accountNumber(), revealed.iban());
    }

    // ----------------------------------------------------------------------------- documents

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @GetMapping(E + "/documents")
    ListResponse<HrViews.Document> documents(@PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return new ListResponse<>(documents.list(employeeId));
    }

    /** Multipart upload: {@code file}, {@code documentType}, {@code title}, optional {@code validUntil}. */
    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @PostMapping(path = E + "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<HrViews.Document> upload(
            @PathVariable UUID companyId,
            @PathVariable UUID employeeId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType,
            @RequestParam("title") String title,
            @RequestParam(name = "validUntil", required = false) @Nullable LocalDate validUntil) {
        documents.validate(documentType, title);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest(
                    "The file could not be read.",
                    List.of(FieldViolation.atPointer("/file", "UNREADABLE", "could not be read")));
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        HrViews.Document created = files.attach(
                "hr",
                "employee_document",
                file.getOriginalFilename() == null ? "document" : file.getOriginalFilename(),
                contentType,
                content,
                upload -> documents.attach(employeeId, documentType, title, validUntil, upload));
        return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/employees/" + employeeId
                        + "/documents/" + created.id()))
                .body(created);
    }

    /** The document's content (audit-logged). */
    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @GetMapping(E + "/documents/{documentId}/content")
    ResponseEntity<byte[]> download(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @PathVariable UUID documentId) {
        var content = documents.content(documents.open(employeeId, documentId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.document().contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(content.document().fileName(), java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @RequiresPermission(HrPermissions.EMPLOYEE_MANAGE)
    @DeleteMapping(E + "/documents/{documentId}")
    ResponseEntity<Void> deleteDocument(
            @PathVariable UUID companyId, @PathVariable UUID employeeId, @PathVariable UUID documentId) {
        documents.delete(employeeId, documentId);
        return ResponseEntity.noContent().build();
    }
}
