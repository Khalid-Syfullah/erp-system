package com.erp.platform.idempotency;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.idempotency.persistence.IdempotencyKeyRepository;
import com.erp.platform.tx.TransactionRetry;
import com.erp.platform.web.ApiException;
import com.erp.platform.web.ApiProblem;
import com.erp.platform.web.DatabaseErrorTranslator;
import com.erp.platform.web.FieldViolation;
import com.erp.platform.web.PlatformErrorCode;
import com.erp.platform.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes a business command under an {@code Idempotency-Key} (API.md §10). The key is claimed, the
 * command runs and the response is stored in <b>one</b> transaction, so a committed key always
 * belongs to a committed effect. A concurrent duplicate blocks on the uncommitted key row and then
 * replays the stored response ({@code Idempotent-Replayed: true}); the same key with a different
 * request is {@code 409 IDEMPOTENCY_KEY_REUSED}. Client errors (4xx) are stored after the rollback;
 * server errors and transient conflicts are not, so a retry executes again. Deadlocks and
 * serialization failures are retried transparently ({@link TransactionRetry}).
 *
 * <p>Keys are honored on the business document endpoints that use this executor (ADR-035); the
 * endpoint decides whether a key is required ({@code [I]} in API.md §17).
 */
@Component
public class IdempotencyExecutor {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    public static final Duration RETENTION = Duration.ofHours(24);

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_.:-]{8,128}$");
    private static final Set<String> STORED_HEADERS = Set.of(HttpHeaders.ETAG, HttpHeaders.LOCATION);
    private static final Set<PlatformErrorCode> NOT_STORED = Set.of(
            PlatformErrorCode.RESOURCE_BUSY,
            PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
            PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS);

    private final IdempotencyKeyRepository keys;
    private final TransactionTemplate tx;
    private final TransactionTemplate separateTx;
    private final TransactionRetry retry;
    private final DatabaseErrorTranslator translator;
    private final ProblemResponses problems;
    private final JsonMapper json;
    private final Clock clock;

    public IdempotencyExecutor(
            IdempotencyKeyRepository keys,
            PlatformTransactionManager transactionManager,
            TransactionRetry retry,
            DatabaseErrorTranslator translator,
            ProblemResponses problems,
            JsonMapper json,
            Clock clock) {
        this.keys = keys;
        this.tx = new TransactionTemplate(transactionManager);
        this.separateTx = new TransactionTemplate(transactionManager);
        this.separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.retry = retry;
        this.translator = translator;
        this.problems = problems;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param body the deserialized request body, part of the request fingerprint ({@code null} if none)
     * @param required whether the endpoint requires a key ({@code [I]})
     */
    public ResponseEntity<?> execute(
            HttpServletRequest request, @Nullable Object body, boolean required, Supplier<ResponseEntity<?>> action) {
        String key = request.getHeader(HEADER);
        if (key == null) {
            if (required) {
                throw ApiException.badRequest(
                        "This request requires an Idempotency-Key header.",
                        List.of(FieldViolation.atParameter(HEADER, "REQUIRED", "Idempotency-Key header is required")));
            }
            return retry.run(() -> tx.execute(status -> action.get()));
        }
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest(
                    "Invalid Idempotency-Key header.",
                    List.of(FieldViolation.atParameter(
                            HEADER, "INVALID_VALUE", "must be 8 to 128 characters of A-Z, a-z, 0-9, _ . : -")));
        }
        UUID userId = CurrentContext.requireActor().userId();
        byte[] hash = fingerprint(request, body);
        try {
            return retry.run(() -> tx.execute(status -> claimAndRun(userId, key, hash, action)));
        } catch (RuntimeException failure) {
            storeClientError(request, userId, key, hash, failure);
            throw failure;
        }
    }

    private ResponseEntity<?> claimAndRun(UUID userId, String key, byte[] hash, Supplier<ResponseEntity<?>> action) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!keys.claim(userId, key, hash, now.plus(RETENTION))) {
            Optional<IdempotencyKeyRepository.StoredResponse> stored = keys.find(userId, key, now);
            if (stored.isPresent()) {
                return replay(stored.get(), hash);
            }
            keys.deleteExpired(userId, key, now);
            if (!keys.claim(userId, key, hash, now.plus(RETENTION))) {
                throw new ApiException(
                        PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS,
                        "A request with this Idempotency-Key is in progress.");
            }
        }
        ResponseEntity<?> response = action.get();
        keys.complete(
                userId,
                key,
                response.getStatusCode().value(),
                json.writeValueAsString(storedHeaders(response.getHeaders())),
                response.getBody() == null ? null : json.writeValueAsString(response.getBody()));
        return response;
    }

    private ResponseEntity<?> replay(IdempotencyKeyRepository.StoredResponse stored, byte[] hash) {
        if (!MessageDigest.isEqual(stored.requestHash(), hash)) {
            throw new ApiException(
                    PlatformErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "The Idempotency-Key was already used for a different request.");
        }
        HttpHeaders headers = new HttpHeaders();
        if (stored.headers() != null) {
            json.readTree(stored.headers())
                    .properties()
                    .forEach(e -> headers.add(e.getKey(), e.getValue().asString()));
        }
        headers.add(REPLAYED_HEADER, "true");
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(stored.status()).headers(headers);
        if (stored.body() == null) {
            return builder.build();
        }
        builder.contentType(
                stored.status() >= 400 ? MediaType.parseMediaType(ApiProblem.MEDIA_TYPE) : MediaType.APPLICATION_JSON);
        return builder.body(json.readTree(stored.body()));
    }

    private void storeClientError(
            HttpServletRequest request, UUID userId, String key, byte[] hash, RuntimeException failure) {
        if (TransactionRetry.isRetryableFailure(failure)) {
            return;
        }
        ApiException api = failure instanceof ApiException e
                ? e
                : translator.translate(failure).orElse(null);
        if (api == null
                || !api.errorCode().status().is4xxClientError()
                || (api.errorCode() instanceof PlatformErrorCode code && NOT_STORED.contains(code))) {
            return;
        }
        String body =
                json.writeValueAsString(problems.problem(api.errorCode(), api.getMessage(), request, api.violations()));
        OffsetDateTime now = OffsetDateTime.now(clock);
        separateTx.executeWithoutResult(status ->
                keys.storeFailure(userId, key, hash, api.errorCode().status().value(), body, now.plus(RETENTION)));
    }

    private static Map<String, String> storedHeaders(HttpHeaders headers) {
        Map<String, String> stored = new LinkedHashMap<>();
        for (String name : STORED_HEADERS) {
            String value = headers.getFirst(name);
            if (value != null) {
                stored.put(name, value);
            }
        }
        return stored;
    }

    /** SHA-256 of method, path, query and the canonical (key-sorted) JSON body. */
    private byte[] fingerprint(HttpServletRequest request, @Nullable Object body) {
        String canonical = body == null ? "" : json.writeValueAsString(sorted(json.valueToTree(body)));
        String material = request.getMethod() + " " + request.getRequestURI() + "?"
                + (request.getQueryString() == null ? "" : request.getQueryString()) + "\n" + canonical;
        try {
            return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(e -> fields.put(e.getKey(), sorted(e.getValue())));
            ObjectNode copy = json.createObjectNode();
            fields.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            var copy = json.createArrayNode();
            node.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        return node;
    }
}
