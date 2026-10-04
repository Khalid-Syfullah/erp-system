package com.erp.hr.persistence;

import static com.erp.db.hr.Tables.EMPLOYEE_DOCUMENTS;

import com.erp.db.hr.tables.records.EmployeeDocumentsRecord;
import com.erp.hr.application.HrViews;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** Employee documents; the content is a platform file (DATABASE.md §5.9). */
@Repository
public class EmployeeDocumentRepository {

    private final DSLContext dsl;

    public EmployeeDocumentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public UUID insert(
            UUID companyId,
            UUID employeeId,
            String documentType,
            String title,
            UUID fileId,
            String fileName,
            String contentType,
            long size,
            @Nullable LocalDate validUntil,
            UUID actor) {
        return dsl.insertInto(EMPLOYEE_DOCUMENTS)
                .set(EMPLOYEE_DOCUMENTS.COMPANY_ID, companyId)
                .set(EMPLOYEE_DOCUMENTS.EMPLOYEE_ID, employeeId)
                .set(EMPLOYEE_DOCUMENTS.DOCUMENT_TYPE, documentType)
                .set(EMPLOYEE_DOCUMENTS.TITLE, title)
                .set(EMPLOYEE_DOCUMENTS.FILE_ID, fileId)
                .set(EMPLOYEE_DOCUMENTS.FILE_NAME, fileName)
                .set(EMPLOYEE_DOCUMENTS.CONTENT_TYPE, contentType)
                .set(EMPLOYEE_DOCUMENTS.SIZE_BYTES, size)
                .set(EMPLOYEE_DOCUMENTS.VALID_UNTIL, validUntil)
                .set(EMPLOYEE_DOCUMENTS.CREATED_BY, actor)
                .set(EMPLOYEE_DOCUMENTS.UPDATED_BY, actor)
                .returning(EMPLOYEE_DOCUMENTS.ID)
                .fetchSingle(EMPLOYEE_DOCUMENTS.ID);
    }

    public List<HrViews.Document> list(UUID companyId, UUID employeeId) {
        return dsl.selectFrom(EMPLOYEE_DOCUMENTS)
                .where(EMPLOYEE_DOCUMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_DOCUMENTS.EMPLOYEE_ID.eq(employeeId))
                .orderBy(EMPLOYEE_DOCUMENTS.CREATED_AT.desc())
                .fetch(EmployeeDocumentRepository::toView);
    }

    public Optional<HrViews.Document> find(UUID companyId, UUID employeeId, UUID id) {
        return dsl.selectFrom(EMPLOYEE_DOCUMENTS)
                .where(EMPLOYEE_DOCUMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_DOCUMENTS.EMPLOYEE_ID.eq(employeeId))
                .and(EMPLOYEE_DOCUMENTS.ID.eq(id))
                .fetchOptional(EmployeeDocumentRepository::toView);
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(EMPLOYEE_DOCUMENTS)
                .where(EMPLOYEE_DOCUMENTS.COMPANY_ID.eq(companyId))
                .and(EMPLOYEE_DOCUMENTS.ID.eq(id))
                .execute();
    }

    private static HrViews.Document toView(EmployeeDocumentsRecord r) {
        return new HrViews.Document(
                r.getId(),
                r.getEmployeeId(),
                r.getDocumentType(),
                r.getTitle(),
                r.getFileId(),
                r.getFileName(),
                r.getContentType(),
                r.getSizeBytes(),
                r.getValidUntil(),
                r.getCreatedAt(),
                r.getVersion());
    }
}
