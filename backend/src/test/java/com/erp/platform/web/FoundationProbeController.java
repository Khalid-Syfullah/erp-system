package com.erp.platform.web;

import static com.erp.db.org.Tables.BRANCHES;

import com.erp.platform.context.CurrentContext;
import com.erp.platform.context.RequestContext;
import com.erp.platform.security.AuthenticatedEndpoint;
import com.erp.platform.security.PublicEndpoint;
import com.erp.platform.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that exercise the platform's web conventions end to end. Lives in test
 * sources, so it is never part of the application.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/_test")
class FoundationProbeController {

    record EchoRequest(
            @NotBlank @Size(max = 20) String code,
            @NotNull @DecimalMin("0.00") BigDecimal amount,
            @NotNull @Valid Nested nested,
            List<@NotNull @Valid Line> lines,
            Status status) {}

    record Nested(@NotBlank String name) {}

    record Line(@NotNull @Positive BigDecimal quantity) {}

    enum Status {
        OPEN,
        CLOSED
    }

    record BranchRequest(String code, String name) {}

    private final DSLContext dsl;
    private final TransactionTemplate tx;

    FoundationProbeController(DSLContext dsl, TransactionTemplate tx) {
        this.dsl = dsl;
        this.tx = tx;
    }

    @PublicEndpoint
    @GetMapping("/public")
    Map<String, String> publicPing() {
        return Map.of("status", "ok");
    }

    @AuthenticatedEndpoint
    @GetMapping("/authenticated")
    Map<String, String> authenticatedPing() {
        return Map.of("status", "ok");
    }

    @RequiresPermission("test.resource.read")
    @GetMapping("/permission/read")
    Map<String, String> readPermission() {
        return Map.of("status", "ok");
    }

    @RequiresPermission({"test.resource.read", "test.resource.write"})
    @GetMapping("/permission/write")
    Map<String, String> writePermission() {
        return Map.of("status", "ok");
    }

    @GetMapping("/unannotated")
    Map<String, String> unannotated() {
        return Map.of("status", "should never be served");
    }

    @AuthenticatedEndpoint
    @PostMapping("/echo")
    EchoRequest echo(@Valid @RequestBody EchoRequest request) {
        return request;
    }

    @AuthenticatedEndpoint
    @GetMapping("/boom")
    Map<String, String> boom() {
        throw new IllegalStateException("secret internal detail: password=hunter2");
    }

    @AuthenticatedEndpoint
    @GetMapping("/items/{id}")
    Map<String, String> item(@PathVariable UUID id) {
        throw ApiException.notFound();
    }

    @AuthenticatedEndpoint
    @PutMapping("/versioned")
    ResponseEntity<Map<String, Integer>> versioned(
            @RequestHeader(name = EntityTags.IF_MATCH, required = false) String ifMatch) {
        int current = 3;
        EntityTags.requireMatch(ifMatch, current);
        return ResponseEntity.ok().eTag(EntityTags.forVersion(current + 1)).body(Map.of("version", current + 1));
    }

    /** Inserts a branch inside the given company context, to provoke real database errors. */
    @AuthenticatedEndpoint
    @PostMapping("/companies/{companyId}/branches")
    Map<String, UUID> createBranch(@PathVariable UUID companyId, @RequestBody BranchRequest request) {
        RequestContext context = CurrentContext.get().orElseThrow().withCompany(companyId);
        UUID id = CurrentContext.callWith(
                context,
                () -> tx.execute(status -> dsl.insertInto(BRANCHES)
                        .set(BRANCHES.COMPANY_ID, companyId)
                        .set(BRANCHES.CODE, request.code())
                        .set(BRANCHES.NAME, request.name())
                        .returning(BRANCHES.ID)
                        .fetchOne(BRANCHES.ID)));
        return Map.of("id", id);
    }
}
