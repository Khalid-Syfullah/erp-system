package com.erp.partners.application;

import com.erp.partners.persistence.GroupRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditPort;
import com.erp.platform.context.CurrentContext;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.EntityTags;
import com.erp.platform.web.MergePatch;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Partner groups for customers or suppliers (account determination, price lists). */
@Service
public class PartnerGroupService {

    static final Set<String> PATCHABLE = Set.of("name", "isActive");

    private final GroupRepository groups;
    private final AuditPort audit;

    PartnerGroupService(GroupRepository groups, AuditPort audit) {
        this.groups = groups;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PartnerViews.Group> list(ListQuery query) {
        return groups.list(CurrentContext.requireCompany(), query);
    }

    @Transactional(readOnly = true)
    public PartnerViews.Group get(UUID id) {
        return groups.find(CurrentContext.requireCompany(), id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public PartnerViews.Group create(PartnerCommands.Group command) {
        UUID id = groups.insert(
                CurrentContext.requireCompany(),
                command,
                CurrentContext.requireActor().userId());
        audit.record(AuditEvent.builder("CREATE", "partners")
                .entity("partner_group", id, command.code())
                .detail("appliesTo", command.appliesTo())
                .detail("name", command.name())
                .build());
        return get(id);
    }

    @Transactional
    public PartnerViews.Group patch(UUID id, @Nullable String ifMatch, JsonNode document) {
        UUID companyId = CurrentContext.requireCompany();
        PartnerViews.Group current = groups.lockForChange(companyId, id).orElseThrow(ApiException::notFound);
        EntityTags.requireMatch(ifMatch, current.version());
        MergePatch patch = MergePatch.of(document, PATCHABLE);
        var name = patch.text("name", true, 100);
        var active = patch.bool("isActive");
        patch.throwIfInvalid();
        String newName = name.orElse(current.name());
        boolean newActive = Boolean.TRUE.equals(active.orElse(current.isActive()));
        if (!groups.update(
                companyId, id, current.version(), CurrentContext.requireActor().userId(), newName, newActive)) {
            throw new ApiException(PlatformErrorCode.VERSION_CONFLICT, "The group was modified concurrently.");
        }
        audit.record(AuditEvent.builder("UPDATE", "partners")
                .entity("partner_group", id, current.code())
                .change("name", current.name(), newName)
                .change("isActive", current.isActive(), newActive)
                .build());
        return get(id);
    }
}
