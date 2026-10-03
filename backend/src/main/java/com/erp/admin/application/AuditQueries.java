package com.erp.admin.application;

import com.erp.admin.persistence.AuditLogRepository;
import com.erp.platform.web.paging.ListQuery;
import com.erp.platform.web.paging.PageResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Audit log reads. Visibility is enforced by RLS on admin.audit_log plus the explicit scope here. */
@Service
public class AuditQueries {

    private final AuditLogRepository repository;

    public AuditQueries(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Entries of one company (the active company of the request). */
    @Transactional(readOnly = true)
    public PageResponse<AuditEntryView> listForCompany(UUID companyId, ListQuery query) {
        return repository.find(query, companyId);
    }

    /** All entries, including global events; requires a {@code @GlobalAccess} request. */
    @Transactional(readOnly = true)
    public PageResponse<AuditEntryView> listGlobal(ListQuery query) {
        return repository.find(query, null);
    }
}
