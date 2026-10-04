package com.erp.accounting.application;

import com.erp.accounting.persistence.JournalRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Journals (PRODUCT_SPEC.md §8.1): books with their own gapless numbering per fiscal year. */
@Service
public class JournalService {

    static final Set<String> PATCHABLE = Set.of("name", "isActive");

    private final JournalRepository journals;
    private final AccountingContext context;
    private final AuditPort audit;

    JournalService(JournalRepository journals, AccountingContext context, AuditPort audit) {
        this.journals = journals;
        this.context = context;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountingViews.Journal> list(ListQuery query) {
        return journals.list(context.companyId(), query);
    }

    @Transactional(readOnly = true)
    public AccountingViews.Journal get(UUID id) {
        return journals.find(context.companyId(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public AccountingViews.Journal create(AccountingCommands.Journal command) {
        UUID id = journals.insert(
                context.companyId(), command.code(), command.name(), command.journalType(), false, context.actor());
        audit.record(AuditEvent.builder("CREATE", "accounting")
                .entity("journal", id, command.code())
                .detail("journalType", command.journalType())
                .build());
        return get(id);
    }

    @Transactional
    public AccountingViews.Journal patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = context.companyId();
        AccountingViews.Journal current = get(id);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var active = patch.bool("isActive");
        patch.throwIfInvalid();
        boolean nextActive = Boolean.TRUE.equals(active.orElse(current.active()));
        if (current.system() && !nextActive) {
            throw new ApiException(
                    PlatformErrorCode.RESOURCE_IN_USE,
                    "System journals are used by the posting rules and stay active.");
        }
        String nextName = Objects.requireNonNull(name.orElse(current.name()));
        if (!journals.update(companyId, id, current.version(), nextName, nextActive, context.actor())) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The journal was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "accounting")
                .entity("journal", id, current.code())
                .change("name", current.name(), nextName)
                .change("isActive", current.active(), nextActive)
                .build());
        return get(id);
    }
}
