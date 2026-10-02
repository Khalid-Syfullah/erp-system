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

- Browser sessions use Spring Session JDBC with a `__Host-` HttpOnly cookie and CSRF tokens.
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
| Sessions | Spring Session JDBC |
| Jobs | db-scheduler |
| Rate limits | Bucket4j on PostgreSQL |
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
