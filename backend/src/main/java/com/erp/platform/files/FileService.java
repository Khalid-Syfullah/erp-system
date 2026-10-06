package com.erp.platform.files;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.files.persistence.FileRepository;
import com.erp.platform.tx.AfterCommit;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Files (ARCHITECTURE.md §8.6): the content is written to object storage <em>before</em> the
 * business transaction and then registered in it ({@link #attach}); if the transaction fails the
 * object is deleted again. Uploads are limited in size and to an allowlist of content types, and
 * the declared type must match the content's signature where the format has one. Keys never contain
 * user-supplied names.
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** Content written to object storage but not registered yet. */
    public record Upload(
            UUID companyId,
            String module,
            String entityType,
            String storageKey,
            String fileName,
            String contentType,
            long size,
            String sha256) {}

    /** A file with its content. */
    public record Content(StoredFile file, byte[] bytes) {}

    /** A file with its content as a stream, which the caller closes. */
    public record Stream(StoredFile file, InputStream content) {}

    private final FileStorage storage;
    private final FileRepository files;
    private final FileProperties properties;
    private final TransactionTemplate tx;

    FileService(FileStorage storage, FileRepository files, FileProperties properties, TransactionTemplate tx) {
        this.storage = storage;
        this.files = files;
        this.properties = properties;
        this.tx = tx;
    }

    /**
     * Writes the content, then runs {@code work} (a transactional service call that registers the
     * upload with {@link #register}); deletes the object again when {@code work} fails.
     */
    public <T> T attach(
            String module,
            String entityType,
            String fileName,
            String contentType,
            byte[] content,
            Function<Upload, T> work) {
        Upload upload = put(module, entityType, fileName, contentType, content);
        try {
            return work.apply(upload);
        } catch (RuntimeException e) {
            discard(upload);
            throw e;
        }
    }

    /** Validates and writes the content to object storage; called outside a transaction. */
    public Upload put(String module, String entityType, String fileName, String contentType, byte[] content) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Object storage is written outside database transactions");
        }
        UUID companyId = CurrentContext.requireCompany();
        String type = contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        String name = sanitize(fileName);
        validate(name, type, content);
        String key = "company/" + companyId + "/" + module + "/" + entityType + "/" + UUID.randomUUID();
        storage.put(key, content, type);
        return new Upload(companyId, module, entityType, key, name, type, content.length, sha256(content));
    }

    /**
     * Writes a file the application generated (a report export, not a user upload) from local disk,
     * streaming it; called outside a transaction. Its type is set by the generator and its size is
     * limited by the caller, so the upload allowlist does not apply.
     */
    public Upload putGenerated(String module, String entityType, String fileName, String contentType, Path content) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Object storage is written outside database transactions");
        }
        UUID companyId = CurrentContext.requireCompany();
        String type = contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        String name = sanitize(fileName);
        try {
            long size = Files.size(content);
            if (size == 0 || name.isEmpty()) {
                throw new IllegalArgumentException("A generated file needs content and a name");
            }
            String key = "company/" + companyId + "/" + module + "/" + entityType + "/" + UUID.randomUUID();
            storage.put(key, content, type);
            return new Upload(companyId, module, entityType, key, name, type, size, sha256(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Records the upload as belonging to {@code entityId}, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredFile register(Upload upload, UUID entityId) {
        UUID companyId = CurrentContext.requireCompany();
        if (!companyId.equals(upload.companyId())) {
            throw new IllegalStateException("The upload belongs to another company");
        }
        return files.insert(
                companyId,
                upload.module(),
                upload.entityType(),
                entityId,
                upload.fileName(),
                upload.contentType(),
                upload.size(),
                upload.sha256(),
                upload.storageKey(),
                CurrentContext.get()
                        .map(RequestContext::actor)
                        .map(a -> a.userId())
                        .orElse(null));
    }

    /** Best-effort removal of content whose registration failed. */
    public void discard(Upload upload) {
        try {
            storage.delete(upload.storageKey());
        } catch (RuntimeException e) {
            log.warn("Could not delete orphaned object {}", upload.storageKey(), e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<StoredFile> find(UUID fileId) {
        return files.find(CurrentContext.requireCompany(), fileId);
    }

    /**
     * The metadata (company-scoped, read in its own short transaction) and the content, fetched from
     * object storage outside any transaction.
     */
    public Optional<Content> read(UUID fileId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Object storage is read outside database transactions");
        }
        UUID companyId = CurrentContext.requireCompany();
        Optional<StoredFile> file = tx.execute(status -> files.find(companyId, fileId));
        return file == null ? Optional.empty() : file.map(f -> new Content(f, storage.get(f.storageKey())));
    }

    /** Like {@link #read}, but streams the content instead of loading it into memory. */
    public Optional<Stream> open(UUID fileId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Object storage is read outside database transactions");
        }
        UUID companyId = CurrentContext.requireCompany();
        Optional<StoredFile> file = tx.execute(status -> files.find(companyId, fileId));
        return file == null ? Optional.empty() : file.map(f -> new Stream(f, storage.open(f.storageKey())));
    }

    /** Removes the metadata in the caller's transaction and the content after the commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(UUID fileId) {
        UUID companyId = CurrentContext.requireCompany();
        files.find(companyId, fileId).ifPresent(f -> {
            files.delete(companyId, fileId);
            AfterCommit.run(() -> storage.delete(f.storageKey()));
        });
    }

    private void validate(String name, String type, byte[] content) {
        if (content.length == 0) {
            throw invalid("/file", "EMPTY_FILE", "must not be empty");
        }
        if (content.length > properties.maxSize().toBytes()) {
            throw new ApiException(
                    PlatformErrorCode.PAYLOAD_TOO_LARGE,
                    "The file exceeds " + properties.maxSize().toMegabytes() + " MB.");
        }
        if (!properties.allowedContentTypes().contains(type)) {
            throw invalid("/file", "CONTENT_TYPE_NOT_ALLOWED", "must be one of " + properties.allowedContentTypes());
        }
        if (!signatureMatches(type, content)) {
            throw invalid("/file", "CONTENT_TYPE_MISMATCH", "content does not match the declared type " + type);
        }
        if (name.isEmpty()) {
            throw invalid("/fileName", "INVALID_VALUE", "must not be empty");
        }
    }

    /** PDF, PNG, JPEG and the ZIP-based Office formats are recognised by their first bytes. */
    static boolean signatureMatches(String type, byte[] c) {
        return switch (type) {
            case "application/pdf" -> startsWith(c, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> startsWith(c, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/jpeg" -> startsWith(c, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                startsWith(c, new byte[] {'P', 'K', 0x03, 0x04});
            default -> true;
        };
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** The last path segment without control characters, at most 255 characters. */
    static String sanitize(@Nullable String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "").strip();
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static ApiException invalid(String pointer, String code, String message) {
        return ApiException.validationFailed(
                "The file is invalid.", List.of(FieldViolation.atPointer(pointer, code, message)));
    }

    private static String sha256(Path content) throws IOException {
        try (InputStream in = Files.newInputStream(content)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
