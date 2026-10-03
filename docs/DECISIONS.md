# ERP System — Decisions, Resolved Conflicts and Open Questions

Status: **Living document**. Every deviation from or change to the specification adds an ADR here.

ADR format: **Context → Decision → Consequences**. Status is one of Accepted, Superseded or Proposed.

---

## 1. Architecture decision records

### ADR-001 — Technology stack (Accepted)

**Context**

The repository was empty, so there were no stack constraints. Requirements emphasize correctness, transactional integrity, maintainability, strong module boundaries and long-term operability for a medium-sized organization.

**Decision**

- **Backend:** Java 25 LTS, Spring Boot 4.x (4.1.1 since Phase 2; see ADR-026), Spring Modulith, jOOQ and Flyway, on PostgreSQL 18.
- **Frontend:** React 19, TypeScript and Vite.

**Rationale**

- `BigDecimal` is native.
- The transaction management is mature and declarative.
- Spring Modulith directly supports the modular-monolith requirements:
  - module verification
  - a transactional outbox (Event Publication Registry)
  - module-scoped integration tests
  - event externalization for future extraction
- Spring Security provides sessions, CSRF and headers.
- The ecosystem is large, and staff are available.
- Java 25 is already installed on the development machine.

**Alternatives considered**

| Alternative | Why not |
|---|---|
| TypeScript/NestJS | One language overall, but weaker decimal and transaction ergonomics and no first-class module verification or outbox. |
| .NET 9 | Comparable strength; no team or tooling signal favoured it. |
| Django | Weaker module boundary enforcement and typing. |

**Consequences**

- The project uses two languages, Java and TypeScript.
- The frontend client is generated from OpenAPI to bridge the gap.

### ADR-002 — Modular monolith, one PostgreSQL schema per module (Accepted)

**Decision**

- One deployable and one database.
- Module = top-level Java package = PostgreSQL schema.
- Modules interact through `api` facades, `events` and ports only. The allowed dependency graph is in ARCHITECTURE.md §5.2.

**Consequences**

- There are ACID transactions across modules.
- Extraction is possible later: schema per module, interface-only coupling and versioned events.
- Cross-schema FKs exist only toward upstream modules. They must be replaced on extraction.

### ADR-003 — jOOQ instead of JPA/Hibernate (Accepted)

**Decision**

Use SQL-first, type-safe jOOQ with code generated from the migrated schema.

**Rationale**

ERP correctness depends on:

- explicit locking: `FOR UPDATE` ordering, `FOR SHARE`
- explicit update statements (`WHERE version = ?`)
- set-based reporting SQL
- predictable transactions

JPA's dirty checking, lazy loading and flush ordering hide these.

**Consequences**

- Repositories are written by hand.
- Aggregates are mapped explicitly.

### ADR-004 — Company as the isolation unit; one organization per deployment; RLS as defense in depth (Accepted)

**Decision**

- Every business row has `company_id`.
- The API path carries the company.
- PostgreSQL RLS uses the `app.company_id` transaction setting.
- Composite FKs enforce same-company references.
- One deployment serves one organization (a group of companies).

**Consequences**

- Cross-company reporting requires explicit global-access paths.
- SaaS multi-organization hosting would need an `organization_id` tier (Q-1).

### ADR-005 — Accounting is downstream; GL postings by synchronous in-transaction event listeners (Accepted)

**Context**

- Operational documents must post to the GL atomically.
- Accounting must not be called by operational modules, to avoid cycles and keep them independent.
- Accounting is built in Phase 8, after the operational modules.

**Decision**

- Operational modules publish self-contained posting events.
- Accounting handles them with synchronous `@EventListener`s inside the publisher's transaction.
- Async reliable listeners (`@ApplicationModuleListener`) are used only for non-critical side effects.

**Consequences**

- An Accounting failure (missing mapping, closed period) rolls back the operational document. This is intended.
- Extracting Accounting later would make the GL eventually consistent (ARCHITECTURE.md §9).
- Event contracts must be complete. This is protected by contract snapshot tests.

### ADR-006 — Server-side sessions for browsers; opaque hashed API tokens for integrations (Accepted)

**Decision**

- Browser sessions are server-side rows (`auth.sessions`, ADR-028; originally planned with Spring Session JDBC) behind a `__Host-` HttpOnly cookie, with CSRF tokens.
- Integrations use bearer API tokens with a prefix, SHA-256 hash, expiry and scopes.
- There are no JWTs.

**Rationale**

- Revocation is immediate.
- No token is held in browser storage.
- No key distribution is needed.

**Consequences**

- There is one session DB read per request. This is acceptable, and can be cached in-process for a short time if needed.

### ADR-007 — Partners module for customers and suppliers (Accepted)

**Context**

- Customers and suppliers are needed by Sales, Procurement and Accounting (AR/AP).
- Putting customers in Sales would make Accounting depend on Sales for master data, and putting suppliers in Procurement would do the same for Procurement.

**Decision**

- Add a supporting **Partners** module (L2).
- A shared `partners` identity has optional `customers` and `suppliers` profiles.

**Consequences**

- One more module than the scope list named.
- One entity can be both a customer and a supplier.

### ADR-008 — Exact numeric types and rounding (Accepted)

**Decision**

- `numeric(19,4)` for amounts, rounded to currency minor units.
- `numeric(19,6)` for unit prices and costs.
- `numeric(18,6)` for quantities.
- `numeric(19,10)` for exchange rates.
- `BigDecimal` in Java.
- Decimal **strings** in JSON.
- HALF_UP by default; HALF_EVEN is configurable per company.
- Tax rounding is per line by default.
- Floating-point types are banned by CI checks.

### ADR-009 — Moving-average costing; no negative stock (Accepted)

**Decision**

- Valuation is per (company, variant) with moving-average cost.
- Negative stock is disallowed, by CHECK constraints and by the service.
- FIFO, standard cost and landed costs are deferred.

**Consequences**

- Valuation is simple and auditable.
- Users must receive stock before they can issue it, so go-live requires opening stock.

### ADR-010 — Master data is company-scoped (Accepted, pending Q-5)

**Decision**

Products, partners, categories, tax codes and the CoA belong to one company. UoMs, currencies and countries are global.

**Consequences**

- Groups whose companies share catalogues must maintain them per company. A "copy to company" tool can be added later.
- If shared master data is required (Q-5), a group-level ownership tier is needed.

### ADR-011 — Ledger plus derived balances with pessimistic locks under READ COMMITTED (Accepted)

**Decision**

- Ledgers are append-only. Balance tables are updated in the same transaction under `FOR UPDATE`, in the global lock order.
- SERIALIZABLE isolation is not used globally.
- There is a retry policy for deadlocks and serialization failures.
- Nightly reconciliation jobs verify the invariants.

### ADR-012 — Gapless document numbering through a locked sequence table (Accepted, revisit at Q-15)

**Decision**

- All document numbers are gapless per (company, type, scope), assigned at the transition out of draft.
- Numbering uses `platform.document_sequences` with `FOR UPDATE`, held until commit.

**Consequences**

- Postings of the same document type in a company serialize from the moment the number is taken until commit.
- This is acceptable at the target scale (ARCHITECTURE.md §10).
- If contention is measured (Phase 12), non-legal documents (POs, deliveries, movements, journal entries) can switch to gap-tolerant numbering. Legal documents (invoices, credit notes) stay gapless.

### ADR-013 — UUIDv7 primary keys (Accepted)

**Decision**

`uuid` keys, generated by PostgreSQL 18's `uuidv7()` or by the application.

**Rationale**

- The keys are globally unique, which helps future extraction and idempotent creation.
- They are time-ordered, which gives index locality.

### ADR-014 — Unallocated payments stay on the control account as negative open items (Accepted)

**Decision**

- Unallocated payment amounts post to AR or AP control and create a negative open item that can be allocated later.
- The CUSTOMER_ADVANCE and SUPPLIER_ADVANCE accounts are reserved for future explicit prepayment documents.

**Consequences**

- The invariant "control account = Σ open items" holds without exceptions.

### ADR-015 — Revenue recognized at invoice, COGS at delivery; no unbilled accrual in v1 (Accepted, pending Q-16)

**Consequences**

- Revenue and COGS can briefly sit in different periods. An "uninvoiced deliveries" report makes this visible.
- Accrual can be added later as a period-end adjusting job.

### ADR-016 — Audit log is append-only, partitioned, and written in the business transaction (Accepted)

**Decision**

- Monthly partitions.
- The application role has INSERT and SELECT only, and a trigger guards against changes.
- Expired partitions are archived to S3 with Object Lock before being dropped.
- A cryptographic hash chain is **deferred**: it would serialize audit writes, and the DB privilege model plus Object Lock is sufficient for v1.

### ADR-017 — PostgreSQL as the only stateful dependency (besides object storage) (Accepted)

**Decision**

| Need | Implementation |
|---|---|
| Sessions | `auth.sessions` table (ADR-028) |
| Jobs | db-scheduler |
| Rate limits | Authentication: counters in PostgreSQL; general API: per-instance windows (ADR-029) |
| Outbox | Modulith Event Publication Registry |
| Search | pg_trgm |

There is no Redis, Kafka or Elasticsearch in v1.

**Consequences**

- Operations are simple. Each component has a documented upgrade path (ARCHITECTURE.md §8.3).

### ADR-018 — Company in the URL path; 404 masking for out-of-scope resources (Accepted)

**Decision**

`/api/v1/companies/{companyId}/...`.

**Rationale**

- The context is explicit, auditable and linkable.
- There is no hidden header state.

Out-of-scope resources, whether in another company, an out-of-scope branch, or of a type the caller cannot read, return 404.

### ADR-019 — Cursor pagination by default (Accepted)

**Decision**

- Lists use keyset cursors that are HMAC-signed.
- The total count is opt-in.

**Rationale**

Pages stay stable under concurrent inserts, and there are no deep `OFFSET` scans.

### ADR-020 — Tax model v1: one rate per tax code, line-level, exclusive or inclusive prices (Accepted, pending Q-2)

**Decision**

- No compound or multi-component taxes.
- No withholding taxes on sales.
- No e-invoicing.

**Consequences**

- Jurisdictions with complex tax rules need an extension. The tax computation is isolated behind a `TaxCalculator` interface in Org's `api` package.

### ADR-021 — Credit notes and debit notes share tables with invoices and bills (Accepted)

**Decision**

- `sales.invoices.document_type` ∈ {INVOICE, CREDIT_NOTE}.
- `procurement.supplier_bills.document_type` ∈ {BILL, DEBIT_NOTE}.
- Amounts are stored positive, and the GL sign is derived from the type.

**Consequences**

- The line, tax, numbering and PDF logic are shared.
- Numbering uses separate sequences per document type.

### ADR-022 — Same-currency allocation only (v1) (Accepted)

**Decision**

- A payment, or a netting item, must be in the same currency as the open item it settles.
- Realized FX differences between rates are posted automatically.

**Consequences**

Cross-currency settlement is a future feature.

### ADR-023 — Same-origin SPA; CORS disabled (Accepted)

**Decision**

- The frontend and API are served from one origin: `/` for the SPA and `/api` for the backend.
- This allows `SameSite` cookies and needs no CORS.

### ADR-024 — Org core delivered in Phase 2 (Accepted)

**Context**

Auth (Phase 3) scopes role assignments to companies and branches.

**Decision**

- Phase 2 delivers `org.companies`, `org.branches`, `org.currencies` and `org.countries`, plus a minimal `OrgFacade`.
- Phase 4 completes Org.

### ADR-025 — Phase 2 delivers only foundation pieces that have a consumer (Accepted, Phase 2)

**Context**

DEVELOPMENT_PLAN.md listed more kernel capabilities for Phase 2 than the Phase 2 brief asked for. The extra items were idempotency, numbering, audit, events, files, crypto, the job scheduler, tracing, company/branch endpoints, and CodeQL. The brief also said: no premature abstractions, no unnecessary dependencies, and no fake implementations to make tests pass. Most of those items have no caller until a later phase. Company and branch endpoints cannot be used safely before authentication exists.

**Decision**

Phase 2 delivers:

- configuration and its validation
- the database role model, migrations and RLS
- the transaction context
- logging and redaction
- the error model and validation
- list conventions
- ETags
- the security skeleton
- the org core tables plus the reference-data endpoints
- tests, Docker and CI

Every other planned item moves to the phase of its first consumer. The table at the end of DEVELOPMENT_PLAN.md Phase 2 maps each one, and the receiving phases list it as "carried over".

**Consequences**

- The phase exit criteria changed: the 50-way numbering test and the idempotency API tests move with their features.
- Nothing in the architecture changes.

### ADR-026 — Spring Boot 4.1 and Jackson 3 (Accepted, Phase 2)

**Context**

ARCHITECTURE.md named Spring Boot 4.0.x. When Phase 2 started, 4.1.1 was the current GA release, and 4.0 was nearer the end of its support window.

**Decision**

- Spring Boot 4.1.1 with Spring Modulith 2.1.1 (the matching line).
- Versions are pinned in `backend/gradle/libs.versions.toml`, and Gradle lockfiles are committed.
- Boot 4 uses **Jackson 3** (`tools.jackson.*` packages; the annotations stay in `com.fasterxml.jackson.annotation`). The strict JSON rules (API.md §7, §11) are implemented with Jackson 3 APIs.

**Consequences**

- Upgrades are deliberate: bump the catalog, regenerate the lockfiles (`./gradlew dependencies --write-locks`), and run the full suite.
- **Security overrides.** When the image scan finds a fixable vulnerability in a BOM-managed library, the fix is pinned in `libs.versions.toml` with the CVE IDs in a comment. The override is removed once the Boot BOM catches up. At the end of Phase 2 two overrides are in place:
  - Tomcat 11.0.26, fixing three CRITICAL CVEs in 11.0.24.
  - Jackson 3.1.7, fixing five HIGH CVEs in 3.1.5.

### ADR-027 — The container image is assembled from a jar built outside `docker build` (Accepted, Phase 2)

**Context**

jOOQ code generation (ADR-003) needs a migrated PostgreSQL, which `generateJooq` starts with Testcontainers. That requires a Docker daemon, and none is available inside `docker build`.

**Decision**

- `./gradlew bootJar` runs first, on CI or a workstation where Docker is available.
- `infra/docker/backend.Dockerfile` then extracts the jar's layers into a JRE image that runs as non-root.
- `.dockerignore` admits only the jar.

**Consequences**

- Image builds always follow a Gradle build. CI does this.
- Generated sources are never committed and cannot drift from the migrations.

### ADR-028 — Browser sessions in an application-owned `auth.sessions` table instead of Spring Session JDBC (Accepted, Phase 3; amends ADR-006)

**Context**

ADR-006 and SECURITY.md §3.3 named Spring Session JDBC. Phase 3 needs per-session state that Spring Session does not model: `authenticated_at`, `reauthenticated_at` (step-up), `mfa_verified`, `mfa_enrollment_required` and an absolute expiry. It also needs listing and revoking sessions by user, a cap of 5 concurrent sessions, rotation on password change and step-up, and deletion when a user is disabled. With Spring Session, all of that lives in serialized attributes and `PRINCIPAL_NAME` lookups. The API is stateless apart from this cookie (`SessionCreationPolicy.STATELESS`), so the `HttpSession` integration brings nothing.

**Decision**

- `auth.sessions` holds one row per session: `token_hash` (SHA-256 of the cookie secret), user, timestamps, step-up and MFA flags, IP and user agent.
- The cookie `__Host-erp_session` carries a 256-bit random secret (base64url, 43 characters). Only its hash is stored, so a database read does not yield usable cookies.
- `AuthRequestAuthenticator` (implementing the platform's `RequestAuthenticator` port) resolves the cookie on every request. It enforces the idle timeout (30 minutes) and the absolute timeout (12 hours), and updates `last_seen_at` at most once per minute.
- "Rotation" issues a new secret for the same session row (password change, step-up). Login and MFA completion always create a new session, so fixation is impossible.
- The MFA challenge between the password and TOTP steps is a separate row in `auth.login_challenges` (cookie `__Host-erp_mfa`, 5 minutes, 5 attempts).

**Consequences**

- There are no `spring_session*` tables and no Spring Session dependency.
- The behaviour in SECURITY.md §3.3 is unchanged and covered by `SessionLifecycleIntegrationTest`.
- One indexed lookup by `token_hash` per request.

### ADR-029 — Rate limiting: database-backed counters for authentication, per-instance windows for the general API (Accepted, Phase 3)

**Context**

ARCHITECTURE.md and SECURITY.md §9 planned Bucket4j with a PostgreSQL backend. Two kinds of limits exist:

- authentication limits (login per account and per IP, password-reset requests, token redemption), which an attacker targets across instances
- general per-user and per-token request budgets, which protect capacity

Bucket4j's PostgreSQL proxy adds a dependency and a row lock per request for the second kind. For the first kind, the records already exist (`auth.login_attempts` is mandatory for audit and lockout).

**Decision**

- **Authentication limits** count rows over a sliding window in PostgreSQL, so they are shared by all instances:
  - logins count `auth.login_attempts` by email hash and by IP (5 and 20 per minute)
  - password-reset requests and token redemptions count `auth.throttle_events` (hashed subject, written in their own transaction so that a failing request still counts)
- **General API budgets** (session user 600/min, token 1,200/min or per-token, anonymous 300/min per IP) use an in-memory fixed one-minute window per instance (`FixedWindowRateLimiter`), with IETF `RateLimit-*` headers and `Retry-After`. With N instances the effective budget is up to N times higher. The load balancer limits in SECURITY.md §9 remain the coarse outer layer.
- "burst 100" is not modelled. A fixed window allows at most twice the budget across a window boundary.

**Consequences**

- No Bucket4j dependency. If budgets need to be exact across instances later (for example, metered integrations), the `FixedWindowRateLimiter` can be swapped for a shared store behind the same filter.
- Report-export and upload limits arrive with those features (Phases 4 and 10).

### ADR-030 — The permission catalogue is seeded from SECURITY.md by a repeatable migration and verified by a test (Accepted, Phase 3)

**Context**

DEVELOPMENT_PLAN.md said "permission catalogue sync from code". Syncing at application startup would need DDL/DML rights for `erp_app` on `auth.permissions`, or a startup step running as the migrator. The authoritative list is the table in SECURITY.md §4.2, which also lists permissions for modules that do not exist yet.

**Decision**

- `R__seed_auth_permissions_and_roles.sql` upserts every permission in SECURITY.md §4.2 (with `is_sensitive` by the documented rule) and the 18 system roles of §4.3 with their permissions.
- Permissions are never deleted. Codes that are no longer listed get `deprecated_at`.
- System-role permissions are rewritten on every change of the script. Custom roles are left untouched.
- `PermissionCatalogIntegrationTest` asserts:
  - the active database catalogue equals SECURITY.md §4.2
  - the `is_sensitive` flags follow the documented rule
  - every `@RequiresPermission` code and every `*Permissions` constant is catalogued
  - the system roles equal §4.3, and none holds a global-only permission

**Consequences**

- Adding a permission means editing SECURITY.md and the seed script in the same change. The test fails otherwise.
- `auth.role.manage` cannot change system roles (`409 SYSTEM_ROLE_IMMUTABLE`). Deployments customize access with custom roles.

### ADR-031 — Field encryption delivered in Phase 3; keys from the secret manager (Accepted, Phase 3)

**Context**

ADR-025 moved `platform.crypto` to Phase 4 (partner bank accounts). TOTP secrets (SECURITY.md §3.5) are its first consumer and arrive in Phase 3. SECURITY.md §7.3 describes KMS envelope encryption, but the cloud provider is not decided yet (Q-9).

**Decision**

- `FieldEncryptor`: AES-256-GCM, a random 96-bit nonce, stored as `version || nonce || ciphertext+tag`, with AAD `table.column:row_id`, as SECURITY.md §7.2 specifies.
- Keys come from `ERP_FIELD_ENCRYPTION_KEYS` (`<version>:<base64 32 bytes>[,...]`; the highest version encrypts and every listed version decrypts). In production the variable is required and is delivered by the secret manager.
- Without keys (local and test profiles only), an ephemeral key is generated with a warning. The `prod` profile refuses to start without keys.
- KMS unwrapping of DEKs and the background re-encryption job are deferred until the hosting decision (Q-9) and Phase 12. They change only how keys are loaded, not the stored format.

**Consequences**

- Key rotation works now: add a new version, deploy, and old rows remain readable. Re-encrypting old rows is a manual task until the job exists.

### ADR-032 — Global administration model and first administrator bootstrap (Accepted, Phase 3)

**Context**

SECURITY.md §5 lists cross-company paths (user administration, global audit, company creation) as `@GlobalAccess`. A new installation needs its first system administrator without any open registration endpoint.

**Decision**

- **System administrators** (`auth.users.is_system_admin`) hold the global permissions (`AuthPermissions.SYSTEM_ADMIN_GLOBAL`: the global-only ones plus `auth.role_assignment.manage`) only on endpoints without a company in the path (`/admin/**`, `POST /companies`), and only through a session or a token without a company restriction.
  - They have **no** access to company data without a role assignment in that company (404 like everyone else).
  - They must use MFA.
  - The last active system administrator cannot be disabled or demoted (`409 LAST_SYSTEM_ADMIN`), and nobody can disable or demote themselves.
- `@GlobalAccess` marks the handlers that read company-scoped data across companies (user, role and assignment administration, the global audit log, company creation). It sets `app.global_access` for the transaction after the permission check. It is allowed only together with `@RequiresPermission` (ArchitectureTests, EndpointSecurityMatrixTest). RLS policies that honour it exist only where cross-company reads are part of the design (`admin.audit_log`).
- **Company administrators** manage role assignments in their company (`{c}/role-assignments`), with these limits:
  - they may assign or remove only roles whose permissions they hold themselves (`403 PRIVILEGE_ESCALATION`)
  - they may not change their own assignments (`403 SOD_VIOLATION`)
  - custom roles may not contain global-only permissions
- **No self-registration.** Users are created by invitation (`POST /admin/users`, email link) or as service accounts. The first system administrator is created by a one-shot command: profile `bootstrap-admin` with `ERP_BOOTSTRAP_ADMIN_EMAIL` and `ERP_BOOTSTRAP_ADMIN_PASSWORD`. It runs without a web server, is idempotent (it does nothing if an active system administrator exists), applies the password policy, and audits the creation.
- **Email links** (invitation, password reset) carry the token in the URL fragment (`/reset-password#token=…`), so it never reaches server logs, proxies or the `Referer` header.

**Consequences**

- Global administration and business access are separate duties. A system administrator who also works in a company needs an explicit assignment there.

### ADR-033 — Phase 4 delivers organization management, including HR's organizational slice; Partners and platform carry-overs move later (Accepted, Phase 4)

**Context**

The Phase 4 brief asked for organization management:

- companies, branches and departments, with their hierarchy and activation
- designations / job titles
- the organizational relationships of employees
- multi-organization isolation

DEVELOPMENT_PLAN.md's Phase 4 instead combined the rest of Org with Partners, CSV import and several platform pieces:

- the idempotency store
- the event publication registry and the company-creation seeding pipeline
- S3 files
- `platform.money`
- OpenAPI

DATABASE.md places positions, employees, employment assignments and department heads in the HR module, in Phase 9. The stakeholder chose to:

- do the brief plus the Org reference data that later modules need
- pull a minimal HR slice forward
- schedule the remaining items later

**Decision**

- **Org (complete):**
  - departments (a tree with a cycle guard, optional branch)
  - exchange rates with the lookup rule, tax codes and payment terms with due-date calculation
  - the company rounding settings
  - activation rules for branches and departments
- **HR, organizational slice:**
  - `hr.positions` (designations / job titles)
  - core `hr.employees`: no sensitive personal data and no user link
  - effective-dated `hr.employment_assignments`: branch, department, position, manager
  - `hr.department_heads`
  - the employee status machine, with termination that ends assignments and headships

  Phase 9 adds the rest of HR on the same tables: encrypted personal data with reveal, the user link, bank accounts, documents, leave, self-service, and the termination side effects (user deactivation, payroll event).
- **"Organization" stays the deployment** (ADR-004, Q-1). "Multi-organization isolation" in the brief is company isolation:
  - the company comes from the path, and membership is checked (404 otherwise)
  - RLS and composite company FKs apply on every new table
  - branch scope applies to employees and assignments
- **Rescheduled:**

  | Item | Moves to |
  |---|---|
  | idempotency store, event publication registry with `processed_events`, the company-creation seeding pipeline, `platform.money`, numbering prefixes (`GET/PUT {c}/settings/numbering`) | Phase 5, their first consumers: posted stock movements, inventory seeders, stock values, document numbers |
  | OpenAPI with `x-permission` | Phase 5 |
  | Partners, CSV import, S3 files with MinIO | Phase 6 (Procurement, the first consumer of suppliers and attachments) |
- **HR roles** gain `org.branch.read` and `org.department.read`, so they can place employees in the structure (SECURITY.md §4.3).

**Consequences**

- HR depends on Org's API only, as ARCHITECTURE.md §5.2 allows. Org learns about HR's use of its units through the `OrganizationUsage` port (ADR-034).
- Phase 9 keeps its exit criteria; its HR deliverables shrink by this slice.

### ADR-034 — Usage ports and a lock protocol keep organizational relationships consistent across modules (Accepted, Phase 4)

**Context**

PRODUCT_SPEC.md §4.2 forbids deactivating a branch or department that is still in use. HR (and later Inventory) uses Org's units, but Org must not read their tables, and must not depend on them (no cycles). Checking "in use" and "still active" in two different transactions is a write-skew race: a deactivation and a new assignment could each see the other's old state.

**Decision**

- `org.api.OrganizationUsage` is a port that downstream modules implement:
  - HR reports current or future assignments, active positions and current or future department heads.
  - Inventory will report active warehouses.

  `org.api.TaxCodeUsage` does the same for tax codes, freezing the rate, scope and exemption of a used code. Its document-module implementations arrive in Phases 6–8.
- **Lock protocol** (DATABASE.md §9):
  - A deactivation locks the unit `FOR NO KEY UPDATE`, then asks the ports, then updates.
  - A new use locks the unit `FOR SHARE` (`OrgFacade.branchForUse` / `departmentForUse`, or the HR position lock), then checks that it is active, then inserts.

  Whichever transaction locks first wins; the other sees its committed result.
- Rules that span many rows serialize on transaction-scoped advisory locks per company: department moves (inside the cycle-guard trigger) and reporting lines (HR).

**Consequences**

- Deactivation errors list every use (`409 RESOURCE_IN_USE`).
- Two mutually crossing moves can deadlock. PostgreSQL then aborts one, which is answered with `409 RESOURCE_BUSY` (retryable). Neither order can produce an inconsistent state, and tests cover both outcomes.

---

## 2. Requirement conflicts identified and how they were resolved

| # | Conflict or ambiguity | Resolution |
|---|---|---|
| C-1 | "Invoices, bills, payments" are listed under Accounting, while Sales and Procurement are also listed as owning invoicing. | Invoice and bill **documents** are owned by Sales and Procurement, where the operational context lives. Their **financial effect** (GL entries, AR/AP open items) and **payments** are owned by Accounting (ARCHITECTURE.md §4.2). |
| C-2 | The required phase order puts Accounting (8) after Inventory, Procurement and Sales, yet those need GL postings. | ADR-005: event-driven downstream posting, with contracts fixed in Phase 1 and atomic sync listeners added in Phase 8. There is no production use before Phase 12 (DEVELOPMENT_PLAN.md §2). |
| C-3 | The phase order puts Auth (3) before Organization (4), but RBAC is company-scoped. | ADR-024: the org core moves into Phase 2. |
| C-4 | "Soft deletion only where appropriate" sits alongside immutability of posted records. | No generic `deleted_at`. Master data uses status deactivation, drafts are hard-deletable, and posted records are never deleted (DATABASE.md §2.8). |
| C-5 | Customers and suppliers are listed as entities, but no module owns them in the 10-module list. | ADR-007: the Partners module. |
| C-6 | Audit is part of the Administration module, but every module must write audit records, and Admin must not become a dependency hub. | `AuditPort` lives in the platform kernel. Admin implements it and owns storage and queries. |
| C-7 | "Modules extractable to services" versus atomic GL postings. | The synchronous in-transaction listeners are kept intentionally. The extraction trade-off is documented in ARCHITECTURE.md §9. |
| C-8 | "Organization/tenant boundaries" while the product targets a single medium-sized organization. | Tenant = company, inside one organization per deployment (ADR-004). Multi-organization hosting is Q-1. |
| C-9 | The frontend is built late (Phase 11), but usability is a goal. | APIs are complete and documented by OpenAPI earlier. An optional early UI shell is allowed (DEVELOPMENT_PLAN.md Phase 11 note). |
| C-10 | Gapless numbering versus concurrency and scalability. | ADR-012, with a measured fallback. |
| C-11 | HR depends on Auth (user link), and terminating an employee must disable the user, but Auth cannot listen to HR because that would create a cycle. | HR calls `AuthFacade.deactivateUser` synchronously (ARCHITECTURE.md §7). |
| C-12 | A credit check in Sales needs AR data, which Accounting owns. Accounting depends on Sales events, so a direct call would create a cycle. | Port inversion: Sales defines `CustomerCreditExposurePort`, and Accounting implements it (ARCHITECTURE.md §5.2). |
| C-13 | The Phase 4 brief asks for organization management including designations and employee relationships (HR tables, Phase 9), while the plan's Phase 4 holds Partners and platform pieces. | ADR-033: Org complete plus an HR organizational slice in Phase 4; Partners, CSV import, files, events, idempotency, money and OpenAPI rescheduled to their first consumers. |

---

## 3. Open questions (need business or stakeholder confirmation)

Each question has a **default** that the implementation follows until it is answered. Questions marked ⚠ affect schema or architecture if their answer differs from the default, so they should be answered before the phase listed.

| ID | Question | Default assumed | Answer needed before |
|---|---|---|---|
| Q-1 ⚠ | Must one deployment host multiple independent organizations (SaaS)? | No. One organization with many companies. | Phase 2 |
| Q-2 ⚠ | Which jurisdictions? This determines the tax regime (VAT/GST/US sales tax), e-invoicing mandates, payroll statutory rules, invoice legal content, and data-protection law (GDPR or other). | Generic single-rate tax; no e-invoicing; statutory payroll through a plug-in hook; GDPR-like data handling. | Phase 4 (tax), Phase 9 (payroll) |
| Q-3 | Is SSO (OIDC/SAML) needed, and with which IdP? | Local authentication with TOTP. OIDC can be added later (SECURITY.md §3.7). | Phase 3 |
| Q-4 | Legal retention periods for financial, payroll, HR and audit data. | 10 years for financial and payroll, 7 years for audit, statutory-then-pseudonymize for HR personal data. | Phase 12 |
| Q-5 ⚠ | Should master data (products, partners, CoA) be shared across companies in the group? | No. Company-scoped (ADR-010). | Phase 4 |
| Q-6 | Are inter-company transactions and consolidated financial statements required? | Out of scope for v1. | Before v2 planning |
| Q-7 ⚠ | Are lot/serial tracking or expiry dates needed (e.g. food, pharma, electronics)? | No. Adding them later affects the inventory ledger key. | Phase 5 |
| Q-8 | Costing method: is moving average acceptable to the finance team and auditors? | Moving average (ADR-009). | Phase 5 |
| Q-9 | Hosting, cloud provider and CI platform. | Cloud-agnostic containers with managed PostgreSQL and S3-compatible storage; GitHub Actions. | Phase 2 (CI), Phase 12 (deploy) |
| Q-10 | Confirm the scale targets: users, volumes, availability, RPO and RTO (ARCHITECTURE.md §10). | As documented. | Phase 12 |
| Q-11 | Fiscal calendar shape: 4-4-5, 13 periods, non-monthly periods? | 12 monthly periods; configurable start month. | Phase 8 |
| Q-12 | Are multi-level, configurable approval chains needed, beyond permission plus threshold? | No. Permission and threshold, with SoD (PRODUCT_SPEC.md G-16/G-17). | Phase 6 |
| Q-13 | Which UI languages? Is right-to-left support needed? | English only, i18n-ready. | Phase 11 |
| Q-14 | Organization's preferred Java base package and product name. | `com.erp`, "ERP". | Phase 2 |
| Q-15 | Which documents legally require gapless numbering? | All, for now (ADR-012). | Phase 12 (performance) |
| Q-16 | Revenue recognition requirements (accrual of unbilled deliveries, deferred revenue)? | Revenue at invoice (ADR-015). | Phase 8 |
| Q-17 | Bank integrations: statement import formats (CAMT.053, MT940, CSV) and payment file formats? | Manual reconciliation marks, and a generic CSV payroll bank file. | Phase 8 / 9 |
| Q-18 | Are employee expense claims and reimbursements needed in v1? | No. Direct expense vouchers only. | Phase 8 |
| Q-19 | Leave policy details: negative balances, weekend definition, accrual method. | No negative balance; Saturday and Sunday weekend; annual grant at the start of the leave year. All configurable. | Phase 9 |
| Q-20 | Email delivery provider and sending domain (SPF/DKIM). | SMTP relay, configured per environment. | Phase 3 |
| Q-21 | Should inventory valuation be split by warehouse (different inventory GL accounts per warehouse)? | Supported through account mappings by warehouse. Valuation (average cost) stays company-wide. | Phase 8 |

---

## 4. Consistency review (Phase 1 sign-off checklist)

These were verified across the documents at the end of Phase 1:

- [x] The module list, schemas and dependency table match across ARCHITECTURE.md §4–§5 and DATABASE.md §1, §4.2 and §6.
- [x] Every event in ARCHITECTURE.md §7 has a row in the PRODUCT_SPEC.md §8.6 posting matrix (where it posts), and all fields used by the matrix are present in its payload, including `referenceValueBase` for purchase returns and the reason code for adjustments.
- [x] Movement types are identical in ARCHITECTURE.md §7, DATABASE.md §5.5 and PRODUCT_SPEC.md §6.3.
- [x] Document state machines in PRODUCT_SPEC.md match the `status` CHECK constraints in DATABASE.md §5.
- [x] Every permission referenced in API.md §17 exists in SECURITY.md §4.2.
- [x] The lock order in ARCHITECTURE.md §6.2 matches DATABASE.md §9 and the posting sequence in DATABASE.md §8.1.
- [x] The journal-line immutability trigger is compatible with the posting sequence: lines are flagged before the header becomes POSTED.
- [x] The bank reconciliation does not mutate posted lines; it uses a separate marks table.
- [x] The unallocated-payment treatment is consistent between PRODUCT_SPEC.md §8.6/§8.7 and ADR-014.
- [x] Phase dependencies in DEVELOPMENT_PLAN.md match the module layering, with the org-core adjustment recorded in ADR-024.
