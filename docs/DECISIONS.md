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
  - they may assign or remove only roles whose permissions they hold themselves (`403 PRIVILEGE_ESCALATION`), and an administrator whose own access is limited to some branches only within those branches: an assignment for all branches, or for a branch outside their scope, is an escalation too (added by the Phase 12 audit)
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

### ADR-035 — Phase 5 inventory: scope, platform services and deviations (Accepted, Phase 5)

**Context**

The Phase 5 brief asked for product master data, warehouses and the stock engine:

- opening stock, receipts, issues, transfers, adjustments, reservations and returns
- transaction history and current balances
- auditability, race safety and organization isolation

It excluded Procurement, Sales, Accounting, HR, Payroll and Reporting. DEVELOPMENT_PLAN.md's Phase 5 also carried platform items over from Phases 2 and 4 (ADR-025, ADR-033). Several details were not fixed by the specification.

**Decision**

- **Scope.** Phase 5 delivers:
  - the catalog, warehouses and locations
  - the stock engine (all twelve movement types, ledger, balances, moving-average valuation, reservations, two-step transfers, reversals, adjustment approval)
  - physical counts
  - `InventoryFacade` and the `inventory.stock_movement.posted` event
  - the nightly invariant job
  - gapless numbering, numbering prefixes, idempotency with purge, transaction retry, `platform.money`, OpenAPI
- **Receipts, issues and returns are facade-only.** `PURCHASE_RECEIPT`, `PURCHASE_RETURN`, `SALES_ISSUE` and `SALES_RETURN` are created only by Procurement and Sales through `InventoryFacade` (API.md §17.5). The facade runs in the caller's transaction and refuses a second movement for the same source document (`409 DUPLICATE_SOURCE_DOCUMENT`; the partial unique index decides races). Until those modules exist, integration tests call the facade directly.
- **Reservations are consumed by issue lines.** A `SALES_ISSUE` line names its reservation (`stock_movement_lines.reservation_id`, INV-2), and posting consumes it under the lock order. This replaces a separate `consumeReservation` facade method. An issue that only reserved stock blocks fails with `INSUFFICIENT_STOCK`; `RESERVED_STOCK_CONFLICT` is kept for adjustments (API.md §6.1).
- **No prices on products.** "Pricing fields where appropriate" maps to:
  - costs: line unit costs and the moving average
  - sales prices: price lists in Phase 7 (PRODUCT_SPEC.md §7)
  - purchase prices: purchase-order lines in Phase 6

  Products carry tax codes, units and weight only.
- **Adjustments.**
  - `inventory.settings.adjustment_approval_threshold` (base currency, NULL = none): posting an adjustment whose absolute value exceeds it needs `inventory.adjustment.approve` (`403 ADJUSTMENT_APPROVAL_REQUIRED`, INV-7).
  - Creating, editing, posting, cancelling and deleting ADJUSTMENT and SCRAP movements needs `inventory.adjustment.manage`, so a clerk cannot post someone else's adjustment draft.
  - Count adjustments are posted through the count (`inventory.count.post`).
- **Counts.**
  - No location freeze (the optional part of INV-8).
  - Posting books *counted − current* per line, with the current quantities read from rows locked in the §6.2 order.
  - A COUNT reason code is required at posting.
  - The count date is editable until the count starts; the snapshot is taken at start.
- **Units.**
  - An entered quantity may not have more decimals than its unit's `rounding_scale` (`TOO_PRECISE`).
  - The base quantity is rounded HALF_UP to the base unit's scale (1 LB = 0.454 KG). A quantity that rounds to zero is rejected.
  - Across categories, only a product conversion converts (`422 UOM_NOT_CONVERTIBLE`).
- **Numbering.**
  - Sequences are scoped by the fiscal-year label: the calendar year in which the fiscal year starts (`FiscalYears.label`; Accounting's fiscal years in Phase 8 follow the same rule).
  - Formats are stored per company in `platform.numbering_settings` (a JSON object by document type). Document types are beans (`DocumentType`) with default prefix and padding; inventory defines `STOCK_MOVEMENT` (`SM-{FY}-`, 6) and `STOCK_COUNT` (`SC-{FY}-`, 6).
  - A prefix renders to at most 20 characters, and the padding is 1–12.
- **Idempotency** (API.md §10).
  - The executor claims the key, runs the command and stores the response in one transaction. Concurrent duplicates wait on the uncommitted key row and replay.
  - Client errors are stored after the rollback, except `RESOURCE_BUSY` and `IDEMPOTENCY_*`, which stay retryable.
  - The body is stored as `json` (not `jsonb`), so replays are byte-identical.
  - Keys are honored only on business-document commands that use the executor. Master-data POSTs ignore them, which narrows "optional on all other POSTs".
- **Transaction retry** is the programmatic `TransactionRetry.run`, which wraps a whole transaction and refuses to run inside one. It is applied by the idempotency executor, not by a `@RetryableTransaction` annotation.
- **Events.** `StockMovementPosted` is published synchronously through Spring's event publisher inside the posting transaction. That is what Accounting's synchronous listener (Phase 8) needs. There is no asynchronous consumer yet, so these are deferred to the first asynchronous consumer:
  - the Event Publication Registry
  - `platform.processed_events`
  - the `org.company.created` seeding pipeline

  Inventory needs no seeding: settings fall back to defaults in code, and warehouses seed their locations when created.
- **`stock_movements.partner_id` has no foreign key.** Inventory and Partners are both L2, so the reference is a plain UUID (DATABASE.md §2.5 and §6). The calling module validates the partner.
- **OpenAPI** is generated by springdoc (API only). Each operation carries `x-permission` and its problem responses, and decimals are documented as strings. The document is not served at runtime: springdoc's endpoint has no access annotation, so the endpoint interceptor would deny it. `OpenApiContractTest` writes `build/openapi/openapi.json` and checks it against the handler annotations. Per-endpoint business error codes are summarized by status, not listed exhaustively. The IDOR suite stays on handler introspection (Phase 2 decision).
- **Deferred:**
  - opening-stock CSV import → Phase 6, with the CSV framework (opening stock is entered through `OPENING` movements meanwhile)
  - `v_rpt_*` inventory views → Phase 10, with Reporting, their consumer

**Consequences**

- Procurement and Sales (Phases 6 and 7) build on `InventoryFacade` without schema changes.
- Every stock change has a posted movement, ledger rows, an audit record and an event, in one transaction. The database rejects ledger updates and deletes, changes to posted movements, negative stock and residual value. The nightly check detects drift.
- Hot SKUs serialize on their `warehouse_stock` rows, and gapless numbering serializes postings per company and document type (ADR-012). Phase 12 measures both.

### ADR-036 — Phase 6 procurement and partners: scope, accounting boundary and deviations (Accepted, Phase 6)

**Context**

The Phase 6 brief asked for:

- suppliers and their details
- requisitions with approval
- purchase orders with explicit transitions
- goods receipts that affect inventory correctly
- supplier bills "where appropriate" and supplier payments "where the architecture specifies them"
- authorization, audit, idempotency and concurrency safety

It excluded Sales, a standalone Accounting module, HR, Payroll and Reporting. The plan's Phase 6 also carried Partners, the CSV import framework, S3 files and the event registry (ADR-033, ADR-035). Its example workflow (`Draft → Submitted → Approved → Ordered → Received → Completed`) differs from the documented one.

**Decision**

- **Partners (supplier side).**
  - partners with status (`ACTIVE`, `INACTIVE`, `BLOCKED`; only active partners go on new documents, and open documents can still be completed)
  - addresses and contacts, each with one default
  - partner groups
  - field-encrypted bank accounts: masked lists, reveal with `read_bank`, step-up and a `VIEW_SENSITIVE` audit record
  - supplier profiles: currency, payment terms, default tax code, lead time, and a group that can only be a supplier group (typed FK)
  - `PartnersFacade.supplierForUse` locks the partner `FOR SHARE`

  Customer profiles follow with Sales in Phase 7. The bank-detail change notification waits for the notification pipeline; until then the change is audited with redacted values.
- **The documented workflow is implemented**, not the brief's example (PRODUCT_SPEC.md §7.2):
  - Requisitions: `DRAFT → SUBMITTED → APPROVED | REJECTED`, then `APPROVED → PARTIALLY_ORDERED → ORDERED` as lines are converted. Ordered quantities are derived from the live orders.
  - Orders: `DRAFT → PENDING_APPROVAL → APPROVED` (rejection back to `DRAFT`), then `PARTIALLY_RECEIVED → RECEIVED → CLOSED` (manual close, or automatic when fully received and billed), or `CANCELLED` while nothing has been received or billed.

  Every transition goes through an enum state machine, checks If-Match, takes a row lock and is audited. The database freezes documents once they leave `DRAFT`: only the fulfilment counters and the listed state columns may change (DATABASE.md §8.4). Three clarifications (PRODUCT_SPEC.md §7.2):
  - returns move an order back along its receipt states
  - orders without stockable lines close from `APPROVED`
  - closed orders still take bills
- **Approval authority** is enforced server-side:
  - `requisition.approve` and `purchase_order.approve`
  - `approve_high` above `settings.po_approval_threshold_base`, compared in base currency at the order-date rate
  - segregation of duties: the approver is neither the creator nor the submitter (`403 SOD_VIOLATION`)

  SoD is always on; the per-company switch for small teams (G-17) is not implemented yet.
- **Inventory integration.**
  - A receipt posts through `InventoryFacade.receive` at the PO line's net unit price per base unit, converted at the receipt-date rate (PRC-2). Each line is checked against `(ordered − received + returned) × (1 + over-receipt tolerance)` (PRC-1, the tolerance from Inventory's settings).
  - Posting locks the receipt, then the order. The order lock serializes all receipts, returns and bills of one order (DATABASE.md §9).
  - A receipt is posted once, protected by four layers: its state, Inventory's one-movement-per-source index, the `uq_goods_receipts__stock_movement_id` index and the `Idempotency-Key`.
  - A failing stock posting rolls back everything, including numbers.
  - Purchase returns go out through `returnToSupplier`, with the receipt unit cost as the reference value that clears GRNI.
  - The facade gained `warehouse`, `overReceiptTolerancePercent` and `PostedLine.locationId`.
- **Supplier bills and debit notes are implemented here.** The architecture makes them Procurement documents (ADR-021, ARCHITECTURE.md §4.2).
  - Bill lines invoice a receipt line (received stock), an order line of a service or consumable (PRC-4), or a product directly (PRC-6, with `create_direct`, services and consumables only).
  - `require_receipt_before_bill` is fixed to `true` (CHECK), because stockable goods without a receipt would have nothing to clear GRNI against.
  - Amounts are computed by the server, with per-line base amounts (G-14) and a tax summary.
  - The supplier's invoice number is unique per supplier and document type (case-insensitive, cancelled drafts excepted; PRC-5).
  - The three-way match (PRC-3) compares quantities with what is open and prices per base unit before rounding, within the company tolerances. It is re-run under the order locks at posting, and a failing bill posts only when overridden with a reason (`override_match`).
  - The receipt value a bill clears (GRNI) is tracked on each receipt line (billed, returned and credited quantities and values). It is the receipt cost of the billed quantity; the bill that completes a line takes the exact remainder; and over-billing within the tolerance never clears more than was received. The price difference goes to PURCHASE_PRICE_VARIANCE when Accounting books the event.
  - A debit note credits returned goods (at most what was returned after billing) or services, never more than is left of its bill.
- **No accounting entries are made in Phase 6.** Bills publish `procurement.supplier_bill.posted` and `debit_note.posted` with every field the posting matrix needs (PRODUCT_SPEC.md §8.6). Accounting (Phase 8) books the AP entry and open item from them synchronously. `BillSettlementPort` answers `UNKNOWN` until then. Supplier payments are Accounting documents (ARCHITECTURE.md §4.2) and come with it.
- **Org API additions.**
  - `OrgFacade.exchangeRate`, `currency`, `paymentTerms`, `countryExists`
  - `TaxCodeSummary` with rate, exemption and validity
  - `CompanyProfile.taxRounding`
  - the `TaxCalculator` port of ADR-020 (`StandardTaxCalculator`)

  Procurement implements `TaxCodeUsage` (codes on submitted orders and posted bills are frozen) and `OrganizationUsage` (open documents keep their branch and department in use).
- **API deviations.**
  - Document lines are replaced through the `lines` array of the merge patch, as for stock movements, instead of separate line sub-resources.
  - Conversion takes a `warehouseId`, because requisitions name a branch, not a warehouse.
  - Bank-account lists need `read_bank`.
  - Supplier bills are company-level documents (no branch scope).
  - Requisitions, orders, receipts and returns are branch-scoped.
- **Numbering:** `PR-`, `PO-`, `GRN-`, `PRT-`, `BILL-` and `DN-{FY}-` with six digits. Requisitions and orders are numbered on submit; the other documents on posting, always after the stock movement's number.
- **Deferred:**
  - PO PDF and supplier email → Phase 7/11, with the document rendering and notification pipeline
  - S3 files with MinIO, and the CSV import framework with partner and opening-stock import → when attachments and imports are first needed (Phase 7)
  - the Event Publication Registry and the company seeding pipeline → with the first asynchronous consumer (notifications); every event so far is synchronous
  - procurement reporting views → Phase 10

**Consequences**

- The full procure-to-pay flow runs through the API, and Phase 8 only adds the accounting listeners and the settlement port implementation.
- Concurrent receipts, bills, approvals and conversions of one document serialize on its row locks: tests show exactly one winner, and the counters stay consistent.
- Receipt lines carry six fulfilment counters. The database guards that freeze posted documents allow exactly these columns to change.

### ADR-037 — Phase 7 sales: scope, accounting boundary and deviations (Accepted, Phase 7)

**Context**

The Sales brief asked for customers and customer contacts, quotations with "quotation approval", sales orders with "order approval", shipment/fulfilment, customer invoices, customer **payments** and returns, on the workflow `Quotation → Sales Order → Fulfillment → Invoice → Payment`. It asked for integration with Inventory, Accounting ("handled through the accounting domain") and Organization, for domain logic, APIs, validation, authorization, audit, migrations and tests, and for invalid status transitions to be rejected. The plan's Phase 7 also carried the infrastructure that ADR-036 moved here: the document rendering and notification pipeline (PDFs, emails), the Event Publication Registry, S3 files and CSV imports.

**Decision**

- **Customers** are partners with a customer profile (Partners module, `partners.customers`): currency, payment terms, default sales tax code, an optional credit limit in base currency, an on-hold flag and a group that can only be a customer group (typed FK). `PUT …/partners/{id}/customer-profile` creates (`If-Match: W/"0"`) or replaces it; `GET {c}/customers` lists them. Contacts and addresses are the partner's (Phase 6). `PartnersFacade` gained `customerForUse` (partner row `FOR SHARE`), `customer`, `defaultAddress` and `customerGroupUsable`.
- **The brief's "approvals" map onto the documented workflow** (PRODUCT_SPEC.md §9.2) instead of adding approval states:
  - *Quotation approval* is the customer's answer: `DRAFT → SENT → ACCEPTED | REJECTED | EXPIRED`, `DRAFT | SENT → CANCELLED`. Accepting (within the validity) creates a draft sales order with the quoted lines and prices; the daily job `sales-quotation-expiry` expires sent quotations past `valid_until`.
  - *Order approval* is the confirmation: `DRAFT → CONFIRMED` numbers the order, runs the credit check (SAL-2) and reserves stock (SAL-3). A check that blocks (limit exceeded in `BLOCK` mode, or a customer on hold) is confirmed only with `{overrideCredit: {reason}}` and `sales.order.override_credit`; the result (`PASSED`, `WARNED`, `OVERRIDDEN`), the overriding user and reason are stored on the order. Exposure = open receivables (`CustomerCreditExposurePort`) + the uninvoiced part of the customer's other confirmed orders at their confirmation rates; a transaction-scoped advisory lock per customer serializes concurrent confirmations so that two orders cannot both fit into the same remaining credit.
  - Pricing approvals (SAL-1): a manual price that differs from the price-list price needs `sales.order.override_price`, a discount above `settings.discount_approval_threshold_percent` needs `sales.order.discount_high`. Lines carried over unchanged from a stored document (an edited draft, an accepted quotation) keep their approval. Without a price-list price a manual price is required (`422 PRICE_MISSING`). The list is the explicit one, else the customer group's active list in the currency, else the currency's default list (one per currency); within it the tier with the highest minimum quantity not above the line's wins, in the line's unit or else the base unit.
- **Every transition** goes through an enum state machine, checks If-Match, takes a row lock and is audited; anything else is `409 INVALID_STATE`. The database freezes documents once they leave `DRAFT`: only the fulfilment counters and the listed state columns may change (DATABASE.md §8.5). Clarifications (PRODUCT_SPEC.md §9.2): an order without stockable lines closes from `CONFIRMED` once invoiced; a confirmed order with nothing delivered is cancelled rather than closed unless it was invoiced (ORDERED policy); closing or cancelling releases the remaining reservations and cancels draft deliveries (and, on cancel, draft invoices); an order created from a quotation is cancelled, never deleted.
- **Inventory integration.** Stock moves only through `InventoryFacade`:
  - Confirmation reserves the open quantity of each stockable line (`allowPartial`); the line keeps the reservation ID and a mirror of the reserved quantity, and shows the backorder. `POST …/reserve` retries backorders.
  - A delivery posts a `SALES_ISSUE` through `issue`, consuming the line's reservation, and records the unit cost and value from the moving average (SAL-4: never more than ordered − delivered, checked under the order lock).
  - A sales return posts a `SALES_RETURN` through `returnFromCustomer` at the delivery's unit cost (SAL-7: never more than delivered − returned per delivery line). Returns do not reopen deliveries.
  - Posting locks the document, then the order header, which serializes every delivery, return and invoice of the order (DATABASE.md §9). Each document is posted once: its state, Inventory's one-movement-per-source rule, a unique stock movement per document and the `Idempotency-Key`.
- **Invoices and credit notes** are Sales documents (ARCHITECTURE.md §4.2):
  - An invoice bills lines of one sales order up to their invoiceable quantity (SAL-5): DELIVERED policy — delivered − returned − invoiced for stockable lines; ORDERED policy and non-stockable lines — ordered − returned − invoiced. `POST {c}/invoices/from-order` prefills everything invoiceable, optionally limited to some deliveries. A price or discount other than the order's needs `override_price`. Direct invoices (no order) are for SERVICE products only and need `sales.invoice.create_direct`.
  - A credit note always credits a posted invoice (line by line, never more than invoiced − already credited, never above the invoiced unit price). It may also reference a received sales return; only such credits give the order line back its invoiceable quantity — price-only credits do not.
  - Amounts are computed by the server (G-14), with per-line base amounts and a tax summary; the billing address and the customer's tax registration are snapshots (G-10). Invoices are company-level (no branch scope); quotations, orders, deliveries and returns are branch-scoped through the warehouse's branch.
- **No accounting entries and no customer payments in Phase 7.** Payments are Accounting documents (ARCHITECTURE.md §4.2, PRODUCT_SPEC.md §8.8) and come with it in Phase 8, as the user decided for this phase. Invoices publish `sales.invoice.posted` and credit notes `sales.credit_note.posted` with every field the posting matrix needs (PRODUCT_SPEC.md §8.6); Accounting books the AR entry and open item from them synchronously. `CustomerCreditExposurePort` answers zero and `InvoiceSettlementPort` answers `UNKNOWN` (`GET …/invoices/{id}/settlement`) until Accounting implements them. Cost of goods sold is booked from Inventory's `stock_movement.posted` at delivery (ADR-015). Orders publish `sales.order.confirmed` and `sales.order.cancelled`.
- **API deviations.** Document lines are replaced through the `lines` array of the merge patch (as in Phase 6). Accepting a quotation returns the new order (`201`). Sales returns are created and read with `sales.return.manage`. Delivery and invoice cancel use the `.post` permission, like posting.
- **Numbering:** `QT-`, `SO-`, `DL-`, `SR-`, `INV-` and `CN-{FY}-` with six digits. Quotations are numbered when sent, orders when confirmed, the other documents on posting, always after the stock movement's number.
- **Deferred** (the user chose to defer the infrastructure):
  - invoice and PO PDFs, quotation and invoice e-mail sending (`GET …/pdf`, `POST …/send`), the bank-detail change notification → with the document rendering and notification pipeline
  - the Event Publication Registry, `processed_events` and the `org.company.created` seeding pipeline → with the first asynchronous consumer; every event so far is synchronous
  - S3 files with MinIO, and the CSV import framework (partners, opening stock)
  - sales reporting views → Phase 10
  - automatic draft credit notes on return receipt (a MAY in PRODUCT_SPEC.md §9.2)

**Consequences**

- The order-to-cash flow runs through the API up to the posted invoice and credit note; Phase 8 adds the AR listeners, the two port implementations and customer payments.
- Concurrent deliveries, invoices, credit notes, confirmations and acceptances serialize on row and advisory locks: tests show exactly one winner, the counters stay consistent and reservations never exceed the stock.
- Order lines carry five fulfilment fields (reservation, reserved, delivered, returned, invoiced); the database guards that freeze confirmed orders allow exactly these to change.

### ADR-038 — Phase 8 accounting: scope, the posting API and deviations (Accepted, Phase 8)

**Context**

The Accounting brief asked for the chart of accounts with account types, fiscal years and periods, journal entries and lines, the general ledger and trial balance, AR and AP, customer invoices and supplier bills, payments, expenses, bank accounts and bank transactions, with a posting engine, journal validation, period closing, APIs and tests. Its rules: every posted entry balances, drafts are editable, posted entries are never silently changed and are corrected by reversal, closed periods take no normal postings, financial operations are atomic and exact-decimal and auditable, and other modules implement no accounting logic. It also asked for "a clear accounting service/domain API that Sales and Procurement can use". The plan's Phase 8 still carried the infrastructure that ADR-037 moved here (event registry, document rendering and e-mail, S3, CSV imports, report file exports). The user chose to defer that infrastructure again and to deliver every report as JSON.

**Decision**

- **The accounting API for other modules is the one ARCHITECTURE.md §5.2 allows.** Accounting depends on Sales and Procurement, so they cannot call it. They use it in two ways, and implement no accounting logic themselves:
  - *Events:* they publish their documents (`inventory.stock_movement.posted`, `procurement.supplier_bill.posted` / `debit_note.posted`, `sales.invoice.posted` / `credit_note.posted`), and `OperationalPostings` books them synchronously in the publisher's transaction through `PostingService` per the PRODUCT_SPEC.md §8.6 matrix (ADR-005). A failed posting (closed period, missing mapping) rolls the operational document back. Each event is booked once: `source_event_id` is unique, plus a unique system entry per source document.
  - *Ports they define, implemented by Accounting:* `CustomerCreditExposurePort` (open receivables in base currency), `InvoiceSettlementPort` and `BillSettlementPort` (open amount and status of the document's open item).
  - Inside the module, `PostingService` is the single posting engine: payments, expenses, reversals, FX differences, netting and the year-end close all use it.
- **Posting engine** (DATABASE.md §8.1): validation in memory (ACC-1, ACC-2, accounts postable and in currency, control accounts for system entries only), the period `FOR SHARE` (ACC-4: OPEN; SOFT_CLOSED with `accounting.period.post_soft_closed`, manual entries only where the company allows it; CLOSED never), header as DRAFT, lines, lines marked posted, the gapless number taken late (`JOURNAL:<code>` per journal and fiscal year, `<code>-{FY}-000001`), header flipped to POSTED. The database re-checks all of it: the deferred balance trigger, the immutability triggers, the period trigger and the account trigger. A system entry off by at most the company's rounding tolerance gets one ROUNDING_DIFFERENCE line.
- **Corrections are reversals:** a reversal is a new entry with debits and credits swapped and `reversal_of_id` set, dated in an open period; the original stays POSTED and only receives `reversed_by_id`. A reversal is not reversed again; an entry is reversed once.
- **Subledger** (ADR-022): every invoice, bill, credit and debit note opens an item; a posted payment opens its own negative item for its full amount, and allocations net a negative item against a positive one of the same partner, kind and currency. Each item falls at its own rate; the difference is the realized FX gain or loss, booked as a separate entry. Unallocating and voiding reverse those entries; a voided payment's item ends as `VOIDED`. Control accounts always equal Σ open items in base currency (ACC-6). Open items and allocations are append-only apart from their running amounts and reversal marks.
- **Company setup.** Org publishes `org.company.created` (new `org::events`) synchronously from `CompanyService.create`. Accounting seeds the company in the same transaction: the 31-account `STANDARD_SME` chart (all system accounts), the default mappings, nine journals, settings and the current fiscal year with its twelve periods. Companies that predate Phase 8 are backfilled on application start. `platform.tx.CompanySwitch` runs the seeding with the new company's `app.company_id` so that RLS applies.
- **Period and year close:** close requires no drafts in the period, all earlier periods closed and a level trial balance, and writes the `period_balances` snapshot. Reopen needs a reason, an open fiscal year and goes latest period first; it drops the snapshot. **The year-end close is synchronous**, not a 202 job: with every period closed, it posts the CLOSING entry (P&L into retained earnings) into the closed last period, the only entry the database accepts there (GUC `app.allow_closing_entry`, set only by the year-close service). It then refreshes that period's snapshot, closes the year and opens the next.
- **Additions to the specification:**
  - settings `manual_entry_approval_threshold_base` (G-16 SoD for manual entries: above it, the poster must not be the creator) and `coa_template`
  - `is_system` on accounts and journals (system accounts keep their subtype, postability and status)
  - open item status `VOIDED`
  - `payments.requested_allocations` (a draft's allocations, applied at posting), `payments.open_item_id`, `voided_at` / `voided_by`
  - `payment_allocations.counter_open_item_id` is NOT NULL: every allocation names its settling item
  - the control-account trigger rejects MANUAL, ADJUSTMENT and OPENING lines on control accounts
  - `InventoryFacade.categoryAncestry` / `reasonCode` / `valuationTotalBase` and `PartnersFacade.group`, for account determination, scope validation and the inventory invariant; `DocumentNumberService.next(…, fallback format)` for per-journal sequences; bank account number handling moved from Partners to `platform.banking`
- **API deviations** (API.md §17.8):
  - the account tree is `GET {c}/accounts/tree`
  - open items are `GET {c}/receivables` and `GET {c}/payables` (plus `/{id}` with its allocations), because `@RequiresPermission` cannot pick `ar.read` or `ap.read` by a query filter
  - `GET {c}/bank-accounts/{id}/transactions?from=&to=` is the cash book with reconciliation marks (the brief's bank transactions)
  - `POST {c}/fiscal-years/{id}/close` answers `200` with the closed year
  - reports are JSON only, computed live from posted lines (the snapshots serve period close and later Reporting); amounts carry the ledger scale of four decimals
- **Dependencies:** `accounting` may use `inventory :: api` and `org :: events` in addition to the documented ones.
- **Deferred:**
  - the Event Publication Registry, `processed_events`; the document rendering and notification pipeline; S3 files with MinIO; the CSV import framework; report exports (`?format=csv|xlsx|pdf`) — again with their first asynchronous consumer (user decision)
  - the events `accounting.period.closed` / `reopened` and `accounting.payment.posted` — no consumer before Reporting and notifications
  - PAY_COMPONENT-scoped mappings → Phase 9 (Payroll)
  - the batch supplier payment proposal (optional)
  - an Org-side guard (a port Accounting implements, like `hasPostings` in DATABASE.md §8.3) against changing `fiscal_year_start_month` once fiscal years exist. Until then such a change affects only future `POST {c}/fiscal-years` and fiscal-year labels of new document numbers; existing years, periods and entries are stored and stay as they are. The base currency already cannot change after creation.

**Consequences**

- The Phase 5–7 integration tests and the new accounting suites assert the exact GL lines of every operational flow, and every suite ends with the invariant check (level trial balance, AR = Σ open receivables, AP = Σ open payables, inventory GL = valuation). The daily `accounting-invariants` job runs the same check for every company.
- Seeding in the creating transaction means a company is never visible without its books. The cost is that company creation now also inserts about 70 accounting rows.
- The synchronous year close holds the year's period locks for the duration of one entry and one snapshot, which is acceptable at the documented scale (ARCHITECTURE.md §10).

### ADR-039 — Phase 9 HR and payroll: scope, file storage, the payroll engine and deviations (Accepted, Phase 9)

**Context**

The HR and Payroll brief asked for employee profiles and employment information, departments and designations, attendance, leave (types, balances, requests, approval, history), holidays, employee documents and employment status; and for salary structures and components, employee compensation, payroll periods and processing, deductions, allowances, payslips and payroll reports. Payroll had to allow country-specific rules later without hard-coding them, calculate deterministically and testably, and keep salary data from users without the right permissions. PRODUCT_SPEC.md §1 listed "time and attendance" as out of scope, and payslip PDFs and employee documents need the file infrastructure deferred since Phase 6. The user chose **basic attendance** (records and self-service clock-in/out, not read by payroll) and **file storage plus payslip PDFs now** (e-mail, the event registry and CSV imports stay deferred).

**Decision**

- **Files (platform).** `platform.files` records metadata (owner module and entity, name, type, size, SHA-256, key `company/{companyId}/{module}/{entity}/{uuid}`); content lives behind the `FileStorage` port with an S3 adapter (AWS SDK v2, URL-connection client). `FileService.attach` writes the object *before* the business transaction and registers it inside it, deleting the object if the transaction fails; reads look up metadata in a short transaction and fetch the object outside any. Uploads are limited to 25 MB and an allowlist of types, and PDF, PNG, JPEG and Office content must match its declared type. MinIO's images are no longer published (Docker Hub and quay.io refuse them), so Docker Compose runs SeaweedFS's S3 gateway and the tests Adobe S3Mock; production uses AWS S3 with SSE-KMS. Production requires `ERP_FILES_BUCKET`.
- **Employees.** Personal data (personal e-mail, phone, address as JSON) is part of the record. Date of birth and national ID are field-encrypted (both re-encrypted with the active key whenever either changes); setting them needs `hr.employee.read_sensitive`, responses show only `dateOfBirthSet` and `nationalIdMasked`, and `POST …/reveal` decrypts them after a step-up with a `VIEW_SENSITIVE` audit record. The audit trail records that they changed, never the values. A user is linked once per company (`UNIQUE (company_id, user_id)`, not globally: one person may work for two companies) and only if assigned to the company (`auth::api`, new). Termination cancels leave after the termination date, disables the linked user (all sessions and tokens end) and publishes `hr.employee.terminated`; Payroll ends the compensation at that date.
- **Bank accounts and documents.** Employee bank accounts are field-encrypted, listed masked and revealed (step-up, audited) with `hr.employee.manage_bank`; the first is primary and payroll pays the primary one. Documents (contract, identity, certificate, work permit, review, other) are files; downloads are audited.
- **Leave.** Leave types have an entitlement, `ANNUAL` (pro rata by hire month, to half days) or `MONTHLY` (one twelfth per month) accrual, a carry-forward maximum and `allow_negative_balance`. The leave year is the calendar year (Q-19) and a request stays within one. Balances are sums over the append-only ledger; the accrual (daily job `hr-leave-daily` or `POST {c}/leave-accruals`) is idempotent through unique indexes, and at the first run of a year closes the previous one: its positive balance leaves it (`EXPIRY`) and up to the maximum enters the new year (`CARRY_FORWARD`), so no day counts twice. Days are working days of the employee's branch (company weekend setting, company-wide and branch holidays); a half day is 0.5 of one working day. Submission checks the available balance (balance − other submitted days), approval the balance; submitted and approved leave never overlaps (partial exclusion constraint). HR approves with `hr.leave.approve`; managers approve their direct and indirect reports through `{c}/me/team/leave-requests/{id}/approve` (a separate, `@AuthenticatedEndpoint` path: one annotation cannot say "permission or manager"); nobody decides their own request (`403 SOD_VIOLATION`). Approved leave covering the business date moves an active employee to `ON_LEAVE` at once and back on cancellation; the daily job does the same for leave that starts or ends.
- **Attendance** (new; PRODUCT_SPEC.md §10.1 now includes basic attendance): one record per employee and day (PRESENT, ABSENT, HALF_DAY, REMOTE, ON_LEAVE, HOLIDAY, times, minutes worked), kept by HR (`hr.attendance.read` / `.manage`, new permissions) or clocked in and out by the employee. Payroll does not read it; overtime and unpaid absence are payroll inputs.
- **Payroll engine.** `PayrollCalculator` is a pure function of its request (no clock, no database; equal requests give equal payslips). PAY-1: one segment per compensation in effect, clipped to employment and assignments; FIXED and PERCENT_OF_BASE earnings are prorated by `days ÷ periodDays` (calendar days by default, working days by setting); FIXED deductions and contributions are not prorated. PAY-2 order with gross and taxable gross; a negative net becomes a run issue instead of a payslip, and a run with issues is not approved. PAY-3 rounding per line. Off-cycle runs pay their own inputs only (no FIXED or PERCENT_OF_BASE components).
- **Country rules.** STATUTORY components name a `StatutoryRule` bean by code; the rule receives gross, taxable gross, prorated base, the component's rate and amount, the period, currency, the company's country (new `CompanyProfile.countryCode`) and the proration factor, and returns an amount. v1 ships `NONE` and `FLAT_PERCENT` (rate % of taxable gross, capped by the amount); a country pack adds beans, nothing else changes.
- **Runs.** Calculation is asynchronous (`202`): the run waits in `CALCULATING` and the recurring task `payroll-calculation` claims it (`FOR NO KEY UPDATE SKIP LOCKED`) and calculates the **whole run in one transaction** with batched inserts, so its payslips appear together or not at all (ARCHITECTURE.md §6.2's one-transaction-per-chunk rule is relaxed here; 1,000 employees take about a second). A failure returns the run to DRAFT with the error as an issue. Changing inputs sends a calculated run back to DRAFT. Approval: approver ≠ the user who requested the calculation, and not paid in the run (`403 SOD_VIOLATION`). Posting numbers the run (`PR-{FY}-`), marks the regular run's period PROCESSED and publishes `payroll.run.posted`; marking paid publishes `payroll.run.paid`. Database triggers freeze approved and later runs (only the lifecycle columns change) and their payslips (a posted payslip only gains its PDF).
- **Payslips and the bank file.** PDFs are rendered with OpenPDF directly (not an HTML template with Flying Saucer) by the `payroll-payslip-pdfs` task, or on first download, and stored as files. `payroll.payslip.read` sees all payslips; employees see their own in `{c}/me/payslips` once the run is posted. The bank file is a CSV of net pay per primary bank account for approved, posted and paid runs (`422 MISSING_BANK_ACCOUNT` otherwise), audited, with spreadsheet formulas neutralised.
- **Reports (JSON):** headcount (`{c}/hr-reports/headcount`), attendance summary, payroll run summary (`payroll.report.read`), the register (`payroll.report.read` and `payroll.payslip.read`, PAY-6) and component totals.
- **Accounting.** `PayrollPostings` books `payroll.run.posted` (Dr SALARY_EXPENSE [pay component, department], Dr EMPLOYER_CONTRIBUTION_EXPENSE [component, department]; Cr PAYROLL_DEDUCTION_LIABILITY [component], Cr EMPLOYER_CONTRIBUTION_LIABILITY [component], Cr SALARIES_PAYABLE) and `payroll.run.paid` (Dr SALARIES_PAYABLE / Cr the bank's GL account, plus a payment of kind OTHER numbered `PAY-`). Accounting depends on `payroll :: api` (to validate PAY_COMPONENT mapping scopes) and `payroll :: events`. A closed period or missing mapping refuses the payroll step.
- **Simplifications:** pay schedules and payroll are in the base currency; a compensation's base is the pay of one full period of its schedule; a new compensation ends the open-ended one before it; FINAL_SETTLEMENT runs are not offered (termination prorates the last regular run).
- **Deferred:** the Event Publication Registry and asynchronous event listeners; e-mail (payslip notifications); the CSV import framework; FINAL_SETTLEMENT runs; bank-specific payment formats; employee expense claims (Q-18).

**Consequences**

- The calculation is reproducible: the same compensations, inputs and settings give the same payslips, which `PayrollCalculatorTest` checks on 300 seeded random cases.
- Salary data is reachable only through payroll permissions or the employee's own released payslips; HR roles and responses carry none.
- Object storage is a new runtime dependency for documents and payslip PDFs.

---

### ADR-040 — Phase 10 reporting: report sources, the reporting role, exports and deviations (Accepted, Phase 10)

**Context**

The Phase 10 brief asked for sales, procurement, inventory, accounting and HR reports with filters (dates, organization, company, branch, department, warehouse, customer, supplier, product, account), built on the existing business logic and authoritative data rather than a second implementation of accounting, inventory, sales or payroll calculations; for set-based queries, pagination and caching or read models only where justified, with no data warehouse; for backend authorization; for CSV and, as the architecture specifies, XLSX and PDF exports that stream large data; and for tests of calculations, filters, isolation, permissions, date boundaries, pagination, volume and reconciliation. DEVELOPMENT_PLAN.md adds the `v_rpt_*` views, the `reporting` schema and catalogue, the read-only `erp_reporting` DataSource, the async export framework, saved reports, dashboards and KPIs, and a 2 s p95 budget on volume data at 1/5 of the target.

**Decision**

- **Two report sources, one implementation of every figure.**
  - *Views:* each module publishes `security_invoker` views (`v_rpt_*`) over its own tables (DATABASE.md §11). The module's own rules are encoded once in its view: sales and purchases are posted invoice and bill lines with credit and debit notes negative; GRNI per receipt line is value − returned − (billed − credited), exactly `GoodsReceiptLine.openValueBase()`; stock movements carry the *effective* movement type (a reversal counts as what it reverses); as-of stock and valuation are ledger sums up to the date, as `StockRepository.valuationsAsOf`; headcount uses HR's employment rule. Reports join and aggregate these views with set-based SQL.
  - *Accounting's statements* (trial balance, general ledger, income statement, balance sheet, AR and AP ageing, cash book) stay Accounting's `ReportService`. Accounting gains an `api` package: `FinancialReports` (implemented by `ReportService`) and the `AccountingReports` records (moved from `application`; the journal report record moved to `AccountingViews`). Reporting only lays these out for exports; the dependency `reporting → accounting :: api` was already in the architecture.
- **The reporting role.** Reports read the views through a separate Hikari pool that logs in as `erp_reporting` (`erp.reporting.datasource.*`, default URL the application database, optionally a replica; production requires `ERP_DB_REPORTING_PASSWORD`). It is not a `DataSource` bean, so the application's DataSource, transaction manager and jOOQ context stay the auto-configured ones. Its transactions bind the request's company exactly like the application's (`CompanyScopedTransactionManager`), run READ ONLY at REPEATABLE READ (a page and its totals see one snapshot) with `statement_timeout` (15 s for reports and dashboards, 10 min for exports). `erp_reporting` has SELECT on the views and, because security-invoker views check the invoker, on the columns behind them (column grants for personal and banking tables: no national IDs, contact data or account numbers); it writes nothing. `erp_app` has no privileges on the views. `ArchitectureTests` lets only Reporting use other schemas' generated `VRpt*` classes.
- **Catalogue.** 36 reports in code (`ReportCatalog`: parameters with types, defaults and allowed values; columns with types, totals and sortability; default order; key columns), mirrored by `reporting.report_definitions` (seeded by `R__seed_reporting_report_definitions.sql`) so that saved reports and export jobs reference codes by foreign key. `ReportCatalogIntegrationTest` checks code, seed and SECURITY.md agree, that every view report's query produces its columns, and runs every report for every grouping and filter. Deviations from DATABASE.md §5.11: `permission_codes text[]` (all required; the AR and AP ageing need two), no `parameters_schema` (the catalogue endpoint publishes the parameters from code) and no `is_async` (every report is synchronous and bounded; any can be exported). The validation is a test, not a startup check, because Reporting cannot read `auth.permissions`.
- **Paths.** The catalogue is `GET {c}/reports` and reports run at `GET {c}/reports/{code}`. Accounting's statements keep their existing paths, which are the same `{c}/reports/{code}` (Spring prefers the literal mapping), so the catalogue lists them at their path and Reporting serves only their exports.
- **Synchronous reports.** Parameters are validated against the definition (unknown, repeated, required, type, allowed values, ranges; from ≤ to, at most 3,660 days; all problems at once, 400). Rows come back as objects keyed by column, with the columns' types; decimals are strings. Pages use keyset pagination over the report's sort plus its key columns, with signed cursors bound to the report, parameters and sort (`limit` default 100, maximum 1,000, larger than lists' 200 because report rows are small); totals of numeric columns come with the first page, computed in the same snapshot. Requests are limited to 30 per user and minute per instance (SECURITY.md §9).
- **Authorization.** A report needs all its permissions: `403` otherwise, `404` for an unknown code; the catalogue lists only what the caller can run. Branch scope (SECURITY.md §4.4) applies to branch-scoped data: invoice and bill *lines* by their branch (lines without one only for unrestricted users), orders and receipts by their branch, stock and movements by the warehouse's branch, employees by the branch of their assignment on the relevant date, payslips by branch. Company-level data is not branch-filtered: invoice headers and their payment status, outstanding bills and payables, the general ledger and the company-wide valuation (whoever holds `inventory.valuation.read` sees it whole).
- **Exports.** `POST {c}/reports/{code}/exports` [I] (`reporting.export.create` and the report's permissions) validates the parameters, records the effective values (defaults such as the business date resolved) and the requester's branch scope in `reporting.export_jobs`, audits `EXPORT` and answers `202`. The worker task `reporting-exports` (every 10 s; tests call `ExportJobs`) claims jobs with `SKIP LOCKED`, streams the rows (a server-side cursor, 1,000 rows per fetch) into a temporary file, uploads it with the new streaming `FileService.putGenerated` and registers it in a short transaction; downloads stream from object storage (`FileService.open`). Formats: CSV (UTF-8 with BOM, RFC 4180; text that a spreadsheet would evaluate — starting with `= + - @`, tab or CR — gets a leading apostrophe), XLSX (fastexcel, new dependency: inline strings, typed numbers and dates, flushed every 1,000 rows, a totals row) and PDF (OpenPDF, written in parts, a totals row, at most 10,000 rows). Limits: 1,000,000 rows, 200 MB (failing with `TOO_MANY_ROWS` / `FILE_TOO_LARGE`, never truncating), 10 requests per user and hour (counted in the database, so shared by instances). Files expire after 7 days (task `reporting-export-expiry`). Only the requester sees a job and downloads it, while still holding the report's permissions. A job interrupted by a dead worker fails as `INTERRUPTED`; nothing is retried automatically.
- **Saved reports.** Parameters per user and report, validated and stored as given (so a default "today" stays relative); shared within the company only by holders of `reporting.saved_report.share`; visible only to users who may run the report; changed and deleted only by the owner (merge patch, `If-Match`); audited.
- **Dashboards.** `executive`, `sales`, `finance`, `operations` and `hr` combine seven KPI widgets (sales month to date, receivables overdue, payables due in seven days, stock value, items out of stock or fully committed, open purchase orders with those awaiting approval, headcount), each with its own permission; widgets the caller may not see are not computed. The v1 data model has no reorder points, so "low stock" counts active variants per warehouse with nothing available. Overdue receivables and payables due count positive open items only (unapplied credits are not overdue). KPIs are read in one snapshot and **not cached**: they are indexed aggregates well inside the budget, and a cache would show stale figures right after a posting.
- **Performance.** Indexes for the report paths (posted invoices, receipts and bills by date; posted journal lines by date; the stock ledger by warehouse, variant and date with quantity and type included; invoice lines by invoice; leave by start date). The stock-ledger view joins only with `LEFT JOIN`s on unique keys, so PostgreSQL drops the joins a query does not use. No materialized views or warehouse: the volume test meets the budget without them (DEVELOPMENT_PLAN.md Phase 10).
- **Roles.** `reporting.export.create` goes to the manager roles (inventory, procurement, sales, HR), the accountant, the financial controller, the payroll approver and the auditor (an export creates no business data); `reporting.saved_report.share` to the manager roles and the financial controller.
- **Not in v1:** consolidated multi-company reports (SECURITY.md §4.4), scheduled or e-mailed reports (e-mail stays deferred), the accounting events planned for reporting projections (`accounting.period.closed`/`reopened`, `accounting.payment.posted`: Reporting reads the ledger through its views), GRNI and payment status as of a past date (procurement's counters and Accounting's open items are current; Accounting's ageing is the as-of view of receivables and payables).

**Consequences**

- A figure has one definition: the owning module's view or Accounting's statement. The tests reconcile the reports with the general ledger (sales, COGS, tax, inventory, GRNI, adjustments, expenses, bank), with Accounting's open items and with HR's and Payroll's own figures.
- Reports cannot write, and cannot read another company's rows even through a bug in a query: the database role and RLS stop it.
- A view column change is a contract change for Reporting (DATABASE.md §11).

---

### ADR-041 — Phase 11 frontend: the typed client, unique schema names, the seeded test environment and deviations (Accepted, Phase 11)

**Context**

The Phase 11 brief asked for the ERP frontend on the existing APIs: navigation for Dashboard, Organization, HR, Inventory, Procurement, Sales, Accounting, Payroll, Reports and Administration; reusable components (data table, search, filters, sorting, pagination, forms, modal, drawer, confirmation dialog, date picker, currency input, status badge, notifications, loading, empty and error states); role-aware navigation and permission-aware actions; responsive, accessible, keyboard-friendly screens; consistent validation and error handling; optimistic UI only where safe; no business rules duplicated from the backend; module-by-module verification (tests, type checking, build, API integration). DEVELOPMENT_PLAN.md adds the generated OpenAPI client, the auth flow, ETag and Idempotency-Key handling, i18n-ready strings, decimal.js, the document editor, master-data, audit and attachments patterns, tablet warehouse screens, the report centre, WCAG 2.2 AA with axe, Vitest and Playwright, and verified SPA security headers.

**Decision**

- **Stack.** Vite 8, React 19, TypeScript 5.9 strict (TypeScript 6/7 are not yet supported by `openapi-typescript` and `typescript-eslint`), TanStack Router (file routes, code splitting) and Query, react-hook-form with zod, Tailwind 4 with shadcn/ui (Radix), decimal.js, sonner, lucide. **TanStack Table, `openapi-fetch`, react-day-picker and date-fns are not used** (ARCHITECTURE.md §3 listed some of them): sorting, filtering and paging are all server-side, so the table is a small component; dates use `Intl` and the native date input.
- **A typed client over the generated types instead of `openapi-fetch`.** `src/api/schema.d.ts` is generated from the OpenAPI document (`npm run api:generate`; `api:check` fails when stale). `src/api/client.ts` types paths, path parameters, bodies and responses from it (`companyApi(id).get('/sales-orders/{orderId}', { orderId })`) and adds what `openapi-fetch` lacks for this API: the CSRF token from the `XSRF-TOKEN` cookie (bootstrapped from `/auth/csrf`, refreshed once on `CSRF_INVALID`), `If-Match: W/"<version>"` from the resource's version (or the ETag for settings singletons, `getVersioned`), `Idempotency-Key` per user intent (kept while a dialog retries, renewed after a definitive answer), JSON merge patch for `PATCH`, problem documents as `ApiError`, step-up re-authentication (`403 REAUTHENTICATION_REQUIRED` opens a password dialog, `POST /me/reauthenticate`, one retry with the same key), `401` as an expired session (back to sign-in with the return path) and `403 MFA_ENROLLMENT_REQUIRED` to enrollment. List endpoints take the `filter[...]`, `sort`, `cursor`, `limit`, `q` parameters as a typed builder; their allowlists are the backend's.
- **Unique OpenAPI schema names (backend).** springdoc named schemas after simple class names, so same-named request and response records of different controllers (twelve `LineRequest`s, `Period` in Accounting and Payroll, six `Settings` …) collapsed into one schema and the generated client typed all but one of them wrongly. `OpenApiSchemaNames` gives an ambiguous type its enclosing class without the `Controller`/`Responses`/… suffix (`PurchaseOrderLineRequest`), or its module (`AccountingPeriod`), or the full enclosing name; unique names are unchanged. `OpenApiSchemaNamesTest` fails if two API types share a name. API.md §18 is otherwise unchanged.
- **Server state and permissions.** All business data is TanStack Query state keyed by company; a successful write invalidates the company's cache (a posting changes stock, ledgers and documents of other modules alike). There is **no optimistic UI** for business documents: the server's answer is shown. Navigation and actions follow the user's permissions in the active company (`GET /me`) and the documented state machines (PRODUCT_SPEC.md G-4): they are hidden, never trusted; the server answers `403`/`404`/`409` regardless. Self-service navigation appears when `GET {c}/me/employee` finds the user's employee.
- **No duplicated business rules.** Prices, discounts, taxes, totals, balances, available stock, credit exposure and allocation validity come from the server (the sales editor's "Calculate prices" calls `POST {c}/pricing/quote`). The UI only sums entered figures for display (journal debits and credits, allocation amounts) with decimal.js, pre-fills a line's unit and tax code from the product, and validates shapes (required, number format, length). Server field errors are attached to the inputs by their JSON pointers.
- **Patterns.** A master-data page (list, drawer form, activate/deactivate), a document page (header, state badge, state actions with confirmation and reason, lines, server totals, taxes, settlement) with draft editors (`LinesEditor`), touch-friendly receipt, delivery and count screens (large inputs, card rows, no horizontal scroll at tablet width), an audit history drawer (`GET {c}/audit-log?filter[entityType]&filter[entityId]`, for `admin.audit.read`), and an attachments panel over a storage adapter.
- **Attachments.** API.md §16's generic `{c}/files` and `…/{id}/attachments` endpoints are not implemented by the backend, so document attachments are not offered; the attachments panel serves the HR employee documents API (upload with type and expiry, audited download, removal). Document attachments come with their endpoints (Phase 12 backlog).
- **i18n and formatting.** Every visible string comes from a typed English catalogue (`src/i18n/en.ts`; ADR-043 adds Bangla as the default language); enum values fall back to a humanized label so unknown values from newer servers still display (API.md §2). Numbers, amounts (the currency's minor units, ledger digits kept when significant) and dates use `Intl` in the user's locale and time zone (`/me`); decimal strings are never converted to binary floats, and decimal input is parsed from the user's locale into canonical strings.
- **Report centre.** Parameter forms are generated from the catalogue (`GET {c}/reports`), results use the report's server-side sort and keyset pages with first-page totals, a bar chart shows the first amount by the first text column (one accent colour, sorted, labelled values, as an accessible list), exports are queued and polled, saved reports reopen with their parameters in the URL; Accounting's statements have their own layouts.
- **Accessibility.** WCAG 2.2 AA: landmarks and a skip link, labelled controls with error text tied by `aria-describedby`, `aria-sort` on sortable headers, arrow-key navigation between table rows, focus-trapping dialogs, status text with colour as reinforcement only (contrast-checked tokens in light and dark themes), reduced-motion support. Every page reachable from each seeded user's navigation, every detail page tab and the first record of every document list are scanned with axe (`e2e/smoke.spec.ts`); serious and critical violations fail the suite.
- **Security headers.** `frontend/security-headers.mjs` is the source of the SPA headers of SECURITY.md §10.1: `vite preview` sends them, the web image (`infra/docker/frontend.Dockerfile`, nginx `infra/docker/nginx/spa.conf`, compose service `web`) sends them for the application (not for the proxied API, which keeps its own) with `no-cache` for `index.html` and immutable hashed assets, and `tests/security-headers.test.ts` checks the nginx configuration against the source. `e2e/security-headers.spec.ts` checks the served headers and runs the application under the CSP (no violations) against the production build. Builds write hidden source maps (not referenced from the bundles).
- **Test environment.** `e2e/support/seed.ts` seeds a demo company through the public API only (idempotent): the system administrator (TOTP enrolled by the seed), users Alice (operations roles), Bob (approver and controller roles, for segregation of duties) and Erin (employee self-service, linked to E003), master data, opening stock, a price list, a bank account, HR and payroll setup. Invitations are accepted with tokens read from Mailpit; second factors use the stored TOTP secrets (and respect the replay rule across processes). Sign-in is rate limited (SECURITY.md §9), so the Playwright global setup signs each user in once and the tests reuse the stored cookies. No system role grants `partners.partner.manage`, so the seed creates a custom role `MASTER_DATA` for it (SECURITY.md §4.3 is unchanged).
- **Small corrections found on the way.** `.env.example` named the invitation link `/invite#token=`; the backend sends `/accept-invitation#token=` (the comment is corrected; the SPA reads both tokens from the URL fragment and removes them from the history).

**Consequences**

- The frontend compiles against the exact API contract; a breaking API change fails `npm run typecheck` after `api:generate`.
- The UI never decides a business outcome: a stale permission set or state only shows an action whose call then fails with the server's problem, shown to the user.
- The end-to-end suite needs the local stack (PostgreSQL, Mailpit, object storage, the backend with `ERP_SECURITY_ALLOWED_ORIGINS` including the SPA's origin) and a bootstrapped system administrator; it then seeds and runs unattended.
- Not in v1: document attachments and internal notes (G-8; no endpoints), list CSV export per list (G-18; reports export instead), server-sent job updates (API.md §14 polling is used), a second locale.

---

### ADR-042 — Phase 12 production-readiness audit: remediations that refine the specification (Accepted, Phase 12)

**Context**

A production-readiness audit of the whole repository (architecture, data, security, the four end-to-end workflows, frontend, CI and containers) found defects that the earlier phases' tests did not catch. Most fixes implement the specification as written (an unaudited session sign-out, the missing frontend and dependency gates in CI, fixable CVEs, dead code, a flaky end-to-end test). The ones below refine it.

**Decision**

- **The payroll bank file needs step-up.** It carries every employee's decrypted account number and IBAN, so it is a bulk reveal of Restricted data (SECURITY.md §7): it now requires a password confirmation within five minutes (API tokens, which cannot step up, are refused) and is audited as `VIEW_SENSITIVE` instead of `EXPORT`.
- **Branch scope is part of the escalation guard.** A company administrator whose own assignments are limited to some branches may assign or remove a role only for a subset of those branches; an assignment for all branches (no branch list) or for another branch is `403 PRIVILEGE_ESCALATION`. Before, only the role's permissions were compared, so a branch-restricted administrator could hand out company-wide access.
- **Order cancellation locks drafts `NOWAIT`.** Cancelling or closing a sales or purchase order locks the order, then its draft deliveries, invoices or receipts; posting one of those locks the draft, then the order. The reverse order deadlocked, and PostgreSQL might abort the posting. The drafts are now locked `FOR UPDATE NOWAIT`: a cancellation that meets a posting in flight fails at once with `409 RESOURCE_BUSY` and the posting completes (DATABASE.md §9).
- **Lookups by ID on list endpoints.** The partner, product, variant and employee lists accept `filter[id][in]` (at most 100 IDs; the usual company and branch scope applies). The web application gathers the IDs a page shows and resolves their names with one list request per 100 IDs instead of one `GET` per table cell; a page of 50 documents no longer costs up to 50 extra requests, and a valuation page of thousands of items no longer exhausts the per-user rate limit (600 requests per minute).
- **A plan-stable slow-moving report.** Its per-item last receipt and issue dates come from a `MATERIALIZED` CTE over the stock ledger, so the aggregate runs once whatever plan the page's `ORDER BY … LIMIT` leads to. With some `ANALYZE` samples the report had hit its 15 s timeout in the volume test (twice in full builds) while taking 0.2 s otherwise.
- **Build and supply chain.** The shadcn/ui Tailwind stylesheet (16 KB, MIT) is vendored as `frontend/src/styles/shadcn-tailwind.css`; the `shadcn` CLI package and its dependency tree (seven High advisories) left the build. The web image takes patched Alpine packages at build time. Jackson 2 (springdoc's) is pinned to its patched release like Jackson 3.

**Consequences**

- Downloading the bank file may prompt for the password; the web application's step-up dialog handles it like any reveal.
- A branch-restricted administrator can no longer grant or revoke company-wide roles; a company-wide administrator does that.
- A cancellation racing a posting is answered `409 RESOURCE_BUSY` and can be retried.
- Not addressed by the audit (DEVELOPMENT_PLAN.md Phase 12 deliverables that remain open): load tests against the ARCHITECTURE.md §10 targets at full volume, the restore and failover drills, deployment manifests (`infra/deploy`), alert rules, runbooks, the data migration toolkit, SAST, SBOM, DAST and the external penetration test.

### ADR-043 — Bangla as the default language, with English as the secondary and fallback language (Accepted, Phase 12)

**Context**

The ERP is for organizations in Bangladesh. People there work in Bangla, while the v1 interface was English only (ARCHITECTURE.md §6.10, "UI is English in v1; all strings are externalized"). The application has to be usable in Bangla from the first visit. Users who prefer English must keep it on every device, and localization must not change any value, identifier, calculation or API contract.

**Decision**

- **Two catalogs, one framework.**
  - The web application ships Bangla (`bn-BD`, `src/i18n/bn.ts`) and English (`en`, `src/i18n/en.ts`). Bangla is the default, and English is the fallback for any missing message.
  - Components keep using `t()`, and no Bangla text is written in a component.
  - The language is fixed at load, so a switch reloads the page (docs/LOCALIZATION.md).
- **The profile `locale` is an explicit choice or nothing.**
  - `auth.users.locale` becomes nullable without a default. `null` means "not chosen", and `PATCH /api/v1/me` and `PATCH /api/v1/admin/users/{id}` accept `null`.
  - The old `NOT NULL DEFAULT 'en'` made every account look as if it had chosen English, which would have overridden the Bangla default forever. Migration `V202610120900__auth__optional_locale.sql` therefore clears the stored `'en'`. It was the default and not a language choice, because the interface had only one language. Other values that users set for formatting (such as `en-GB`) are kept.
  - Order of precedence: the profile's choice (every device), then the browser's saved choice, then Bangla. A choice made before sign-in is written to the profile at the next sign-in.
- **Display only.** API values, enum values, error codes, identifiers and data keep their English or ASCII form.
  - Server-provided English labels (reports, dashboards, roles, permissions) are translated in the frontend by their exact text (`serverText`), so the API and the seed data stay as they are.
  - In Bangla, a field error's text comes from its code; in English the server's message is shown.
- **Bangladeshi formatting.**
  - In Bangla, numbers use Bengali digits and the lakh grouping (`Intl` `bn-BD`), and the currency symbol is placed first (`৳২৫,০০০.০০`, a presentation choice over CLDR's trailing symbol).
  - Amounts stay decimal strings, and only their presentation changes.
  - Decimal input also accepts Bengali digits.
  - A profile locale refines formatting only within the running language.
- **Unicode NFC.** The server stores text fields in NFC (`@RawText` excluded) and normalizes `q` and text filters. Bangla letters with two encodings (য়, ড়, ঢ়) then compare equal in storage, uniqueness and search. API.md §5 already trims strings, and NFC is part of the same canonicalization.
- **Font.** Noto Sans Bengali (SIL OFL) is self-hosted, as the CSP requires (`font-src 'self'`).

**Consequences**

- Existing users start in Bangla until they choose English once, after which their choice follows them.
- The other end-to-end specs pin English in their storage state, and `e2e/localization.spec.ts` covers Bangla.
- The manual keeps its English screenshots and tells the reader how to switch.
- A new report, column, permission or role seeded with an English label shows in English in the Bangla interface until `bn.serverText` gets an entry for it.
- The server's problem `detail` stays English. The Bangla interface shows the code's generic text instead.
- Master data (chart of accounts, leave types, products) is shown as entered. The seeded STANDARD_SME chart and the demo company are in English.
- Text stored before this change is not rewritten to NFC. Searches for such text still match unless it contains a decomposed sequence, which is rare in practice because keyboards and IMEs produce NFC.

### ADR-044 — Running the images locally in Safari: no HTTPS upgrade and no Secure cookies over plain HTTP (Accepted, Phase 12)

**Context**

The web image sends the SPA's production headers (SECURITY.md §10.1), including the CSP directive `upgrade-insecure-requests`. A local run of the image (`docker compose --profile app`, http://localhost:8088) has no TLS. Chrome and Firefox treat `localhost` as a secure origin and skip the upgrade. Safari applies it: the HTML loaded, then every script and stylesheet was requested over HTTPS from the plain-HTTP port, nginx answered `400`, and the page stayed empty.

**Decision**

- nginx sets the directive through a `map` on the request's host name. It is left out for exactly `localhost` and `127.0.0.1`, and kept for every other host name.
- **Cookies.** With the page fixed, sign-in still failed in Safari with `403 CSRF_INVALID`. The production profile marks the session, MFA and CSRF cookies `Secure` (with the `__Host-` prefix), and Safari stores no Secure cookies for `http://localhost`. The Docker Compose stack (`infra/compose/docker-compose.yml`) only serves plain HTTP on 127.0.0.1. Its `app` service therefore sets `ERP_SECURITY_SECURECOOKIES=false`, as the `local` profile does. The production default (`erp.security.secure-cookies: true`) is unchanged, and so is every deployment behind TLS.

**Consequences**

- The local Docker run works in Safari: the page renders, and sign-in, including two-step verification, succeeds.
- The Compose file must not be used to serve the application over a network: it binds every port to 127.0.0.1 and now also sends non-Secure cookies.
- Deployments are addressed by their own host name, so they receive the unchanged policy. A deployment reached at `localhost` has no TLS for the directive to enforce anyway.
- `Strict-Transport-Security` is unchanged; browsers ignore it over plain HTTP.
- `frontend/tests/security-headers.test.ts` checks that nginx sends the exact §10.1 policy to every other host and that the exemption covers only these two names. The image's end-to-end header check expects the shorter policy when it runs against localhost.

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
| C-14 | The Phase 5 brief asks for receipts, issues, returns and pricing fields in Inventory, while the specification creates receipts and issues only from Procurement and Sales documents and keeps prices in price lists. | ADR-035: the stock operations exist in Inventory and are exposed to those modules through `InventoryFacade`; product master data carries no prices. |
| C-15 | The Phase 6 brief shows a workflow `Draft → Submitted → Approved → Ordered → Received → Completed` and asks for supplier payments "where the architecture specifies them"; the specification documents a different order workflow and makes payments an Accounting document. | ADR-036: the documented state machines are implemented; payments and all accounting entries come with Accounting (Phase 8), fed by the events Procurement publishes now. |
| C-16 | The Sales brief asks for customer payments and for "quotation approval" and "order approval", on a workflow ending in Payment; the specification makes payments an Accounting document and has no approval states for quotations or orders. | ADR-037: the documented state machines are implemented, with the customer's acceptance and the credit-checked confirmation (plus price, discount and credit overrides) as the approvals; payments come with Accounting (Phase 8), fed by the invoice events Sales publishes now. |
| C-17 | The Accounting brief asks for "a clear accounting service/domain API that Sales and Procurement can use", while Accounting depends on Sales and Procurement and they may not depend on it. | ADR-038: Sales and Procurement publish their documents as events that Accounting books through its single posting engine, and use the ports they define (credit exposure, settlement), which Accounting implements. No other module books entries. |
| C-18 | The HR brief asks for attendance, which PRODUCT_SPEC.md §1 listed as out of scope; payslips and employee documents need the file infrastructure deferred since Phase 6. | ADR-039 (user decision): basic attendance (records, self-service clock-in/out, not read by payroll) and file storage with payslip PDFs now; e-mail, the event registry and CSV imports stay deferred. |
| C-19 | The Reporting brief asks for filters by "organization" and "company" and for reports beyond PRODUCT_SPEC.md §13 (invoice and payment status, warehouse, adjustment, expense, cash and bank, attendance and leave reports); SECURITY.md keeps consolidated multi-company reporting out of v1. | ADR-040: reports run per company (the path's company, with RLS), filtered by branch, department, warehouse, customer, supplier, product, category and account; the additional reports are in the catalogue, each built on its module's view or Accounting's statements. |
| C-20 | The Frontend brief asks for an attachments panel and "Forms" for every module, but API.md §16's generic file and attachment endpoints were never implemented, and PRODUCT_SPEC.md G-8 (notes) and G-18 (CSV export of every list) have no endpoints. | ADR-041: the attachments panel is built and serves the HR employee documents API; document attachments, notes and per-list CSV wait for their endpoints (reports export the same data meanwhile). |

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
