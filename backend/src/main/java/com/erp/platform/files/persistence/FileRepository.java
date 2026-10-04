package com.erp.platform.files.persistence;

import static com.erp.db.platform.Tables.FILES;

import com.erp.db.platform.tables.records.FilesRecord;
import com.erp.platform.files.StoredFile;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/** File metadata (ARCHITECTURE.md §8.6). */
@Repository
public class FileRepository {

    private final DSLContext dsl;

    public FileRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public StoredFile insert(
            UUID companyId,
            String module,
            String entityType,
            UUID entityId,
            String fileName,
            String contentType,
            long size,
            String sha256,
            String storageKey,
            @Nullable UUID actor) {
        return toFile(dsl.insertInto(FILES)
                .set(FILES.COMPANY_ID, companyId)
                .set(FILES.OWNER_MODULE, module)
                .set(FILES.ENTITY_TYPE, entityType)
                .set(FILES.ENTITY_ID, entityId)
                .set(FILES.FILE_NAME, fileName)
                .set(FILES.CONTENT_TYPE, contentType)
                .set(FILES.SIZE_BYTES, size)
                .set(FILES.SHA256, sha256)
                .set(FILES.STORAGE_KEY, storageKey)
                .set(FILES.CREATED_BY, actor)
                .returning()
                .fetchSingle());
    }

    public Optional<StoredFile> find(UUID companyId, UUID id) {
        return dsl.selectFrom(FILES)
                .where(FILES.COMPANY_ID.eq(companyId))
                .and(FILES.ID.eq(id))
                .fetchOptional(FileRepository::toFile);
    }

    public void delete(UUID companyId, UUID id) {
        dsl.deleteFrom(FILES)
                .where(FILES.COMPANY_ID.eq(companyId))
                .and(FILES.ID.eq(id))
                .execute();
    }

    private static StoredFile toFile(FilesRecord r) {
        return new StoredFile(
                r.getId(),
                r.getCompanyId(),
                r.getOwnerModule(),
                r.getEntityType(),
                r.getEntityId(),
                r.getFileName(),
                r.getContentType(),
                r.getSizeBytes(),
                r.getSha256(),
                r.getStorageKey(),
                r.getCreatedAt(),
                r.getCreatedBy());
    }
}
