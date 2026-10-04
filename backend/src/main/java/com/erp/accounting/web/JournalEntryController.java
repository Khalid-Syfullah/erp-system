package com.erp.accounting.web;

import com.erp.accounting.AccountingPermissions;
import com.erp.accounting.application.AccountingCommands;
import com.erp.accounting.application.AccountingListings;
import com.erp.accounting.application.JournalEntryService;
import com.erp.platform.idempotency.IdempotencyExecutor;
import com.erp.platform.security.RequiresPermission;
import com.erp.platform.web.ApiPaths;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.paging.ListQueryParser;
import com.erp.platform.web.paging.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Journal entries (API.md §17.8): manual drafts, posting, reversal; system entries are read-only. */
@RestController
@RequestMapping(ApiPaths.V1 + "/companies/{companyId}/journal-entries")
class JournalEntryController {

    private final JournalEntryService entries;
    private final ListQueryParser parser;
    private final IdempotencyExecutor idempotency;

    JournalEntryController(JournalEntryService entries, ListQueryParser parser, IdempotencyExecutor idempotency) {
        this.entries = entries;
        this.parser = parser;
        this.idempotency = idempotency;
    }

    record LineRequest(
            @NotNull UUID accountId,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal debit,

            @DecimalMin("0") @Digits(integer = 15, fraction = 4) @Nullable BigDecimal credit,

            @Pattern(regexp = "^[A-Z]{3}$") @Nullable String currencyCode,
            @Digits(integer = 15, fraction = 4) @Nullable BigDecimal amountCurrency,
            @Nullable UUID partnerId,
            @Nullable UUID branchId,
            @Nullable UUID departmentId,
            @Nullable UUID taxCodeId,
            @Size(max = 500) @Nullable String description) {}

    record EntryRequest(
            @Nullable UUID journalId,
            @NotNull LocalDate entryDate,

            @Pattern(regexp = "^(MANUAL|ADJUSTMENT|OPENING)$") @Nullable String entryType,

            @NotBlank @Size(max = 500) String description,
            @NotNull @Size(min = 2, max = 500) List<@Valid @NotNull LineRequest> lines,
            @Nullable Boolean postImmediately) {}

    record ReverseRequest(
            @NotNull LocalDate reversalDate,
            @NotBlank @Size(max = 300) String reason) {}

    @RequiresPermission(AccountingPermissions.JOURNAL_ENTRY_READ)
    @GetMapping
    PageResponse<AccountingResponses.JournalEntry> list(
            @PathVariable UUID companyId, @RequestParam MultiValueMap<String, String> parameters) {
        return entries.list(parser.parse(parameters, AccountingListings.JOURNAL_ENTRIES))
                .map(e -> AccountingResponses.JournalEntry.from(e, null));
    }

    @RequiresPermission(AccountingPermissions.JOURNAL_ENTRY_READ)
    @GetMapping("/{entryId}")
    ResponseEntity<AccountingResponses.JournalEntry> get(@PathVariable UUID companyId, @PathVariable UUID entryId) {
        return AccountingResponses.JournalEntry.entity(entries.get(entryId));
    }

    /** A manual draft; with {@code postImmediately} it is posted too (Idempotency-Key required then). */
    @RequiresPermission(AccountingPermissions.JOURNAL_ENTRY_CREATE)
    @PostMapping
    ResponseEntity<?> create(
            @PathVariable UUID companyId, @Valid @RequestBody EntryRequest request, HttpServletRequest http) {
        boolean post = Boolean.TRUE.equals(request.postImmediately());
        return idempotency.execute(http, request, post, () -> {
            var created = entries.create(
                    new AccountingCommands.JournalEntry(
                            request.journalId(),
                            request.entryDate(),
                            request.entryType() == null ? "MANUAL" : request.entryType(),
                            request.description(),
                            request.lines().stream()
                                    .map(l -> new AccountingCommands.EntryLine(
                                            l.accountId(),
                                            l.debit() == null ? BigDecimal.ZERO : l.debit(),
                                            l.credit() == null ? BigDecimal.ZERO : l.credit(),
                                            l.currencyCode(),
                                            l.amountCurrency(),
                                            l.partnerId(),
                                            l.branchId(),
                                            l.departmentId(),
                                            l.taxCodeId(),
                                            l.description()))
                                    .toList()),
                    post);
            return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/journal-entries/"
                            + created.entry().id()))
                    .eTag(EntityTags.forVersion(created.entry().version()))
                    .body(AccountingResponses.JournalEntry.from(created.entry(), created.lines()));
        });
    }

    @RequiresPermission(AccountingPermissions.JOURNAL_ENTRY_CREATE)
    @PatchMapping(path = "/{entryId}", consumes = AccountingResponses.MERGE_PATCH)
    ResponseEntity<AccountingResponses.JournalEntry> patch(
            @PathVariable UUID companyId,
            @PathVariable UUID entryId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode patch) {
        return AccountingResponses.JournalEntry.entity(entries.patch(entryId, ifMatch, patch));
    }

    @RequiresPermission(AccountingPermissions.JOURNAL_ENTRY_CREATE)
    @DeleteMapping("/{entryId}")
    ResponseEntity<Void> delete(
            @PathVariable UUID companyId,
            @PathVariable UUID entryId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch) {
        entries.delete(entryId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission(AccountingPermissions.JOURNAL_ENTRY_POST)
    @PostMapping("/{entryId}/post")
    ResponseEntity<?> post(
            @PathVariable UUID companyId,
            @PathVariable UUID entryId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            HttpServletRequest http) {
        return idempotency.execute(
                http, null, true, () -> AccountingResponses.JournalEntry.entity(entries.post(entryId, ifMatch)));
    }

    /** Reverses a posted manual entry; answers the new REVERSAL entry. */
    @RequiresPermission(AccountingPermissions.JOURNAL_ENTRY_REVERSE)
    @PostMapping("/{entryId}/reverse")
    ResponseEntity<?> reverse(
            @PathVariable UUID companyId,
            @PathVariable UUID entryId,
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody ReverseRequest request,
            HttpServletRequest http) {
        return idempotency.execute(http, request, true, () -> {
            var reversal = entries.reverse(entryId, ifMatch, request.reversalDate(), request.reason());
            return ResponseEntity.created(URI.create(ApiPaths.V1 + "/companies/" + companyId + "/journal-entries/"
                            + reversal.entry().id()))
                    .eTag(EntityTags.forVersion(reversal.entry().version()))
                    .body(AccountingResponses.JournalEntry.from(reversal.entry(), reversal.lines()));
        });
    }
}
