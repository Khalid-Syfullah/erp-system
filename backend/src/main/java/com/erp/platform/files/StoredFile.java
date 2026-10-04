package com.erp.platform.files;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A file's metadata ({@code platform.files}). */
public record StoredFile(
        UUID id,
        UUID companyId,
        String ownerModule,
        String entityType,
        UUID entityId,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageKey,
        OffsetDateTime createdAt,
        @Nullable UUID createdBy) {}
