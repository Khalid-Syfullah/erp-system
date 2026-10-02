# ERP System — Development Plan

Status: **Approved baseline (Phase 1)**.

This plan is dependency-aware. Each phase lists its prerequisites, deliverables, exit criteria and risks. **A phase starts only when the previous phase's exit criteria are met.** Phases run in order and must not be started automatically. Each phase begins with an explicit instruction.

Related: [ARCHITECTURE.md](ARCHITECTURE.md) · [DATABASE.md](DATABASE.md) · [API.md](API.md) · [SECURITY.md](SECURITY.md) · [PRODUCT_SPEC.md](PRODUCT_SPEC.md) · [DECISIONS.md](DECISIONS.md)

---

## 1. Phase overview

```mermaid
flowchart LR
  P1[1 Architecture & spec] --> P2[2 Foundation + Org core]
  P2 --> P3[3 Auth & RBAC]
  P3 --> P4[4 Organization + Partners]
  P4 --> P5[5 Inventory]
  P5 --> P6[6 Procurement]
  P6 --> P7[7 Sales]
  P7 --> P8[8 Accounting]
  P8 --> P9[9 HR & Payroll]
  P9 --> P10[10 Reporting]
  P10 --> P11[11 Frontend]
  P11 --> P12[12 Production readiness]
```

| Phase | Name | Depends on | Relative size |
|---|---|---|---|
| 1 | Architecture and specification | — | M (done) |
| 2 | Foundation (platform kernel, tooling, org core) | 1 | L |
| 3 | Authentication and RBAC | 2 | L |
| 4 | Organization (complete) and Partners | 3 | M |
| 5 | Inventory | 4 | XL |
| 6 | Procurement | 5 | L |
| 7 | Sales | 5, 6 (patterns) | L |
| 8 | Accounting | 4–7 (events) | XL |
| 9 | HR and Payroll | 3, 4, 8 | L |
| 10 | Reporting and analytics | 5–9 | M |
| 11 | Frontend (web SPA) | 3–10 (stable APIs) | XL |
| 12 | Production-readiness audit | all | L |

## 2. Why this order

1. **The foundation comes first.** It provides the transaction manager with RLS context, the error model, idempotency, audit, numbering, events and test infrastructure. Every module relies on these. Building them later would mean retrofitting dozens of endpoints.
2. **Auth before any business module.** Every endpoint must be deny-by-default and permission-annotated from day one. The authz matrix and IDOR test suites are created once in Phase 3, and every later phase adds its endpoints to them automatically.
3. **A small org core is pulled into Phase 2.** Auth's role assignments are scoped to companies (an `auth → org` dependency), so `org.companies`, `org.branches`, `org.currencies` and `org.countries` must exist before Phase 3. Phase 4 completes Org (departments, tax codes, payment terms, exchange rates, settings, numbering configuration) and adds Partners, which every transactional module needs.
4. **Inventory before Procurement and Sales.** Inventory owns the catalog, UoMs, warehouses and the only path to change stock. Procurement (receipts, returns) and Sales (reservations, deliveries, returns) call its facade.
5. **Procurement before Sales.** Stock must come in before it can go out, so realistic Sales tests need receipts. Procurement also sets the document patterns (lines, state machines, three-way quantity tracking) that Sales reuses.
6. **Accounting after the operational modules.** This is deliberate and safe because of the event-driven downstream design (ARCHITECTURE.md §5.2, §6.4):
   - Operational modules **never call Accounting**. They publish posting events with **self-contained payloads**, whose contracts were fixed in Phase 1 (ARCHITECTURE.md §7).
   - Phase 8 adds **synchronous in-transaction listeners**. From then on, each operational document and its GL entry commit atomically.
   - There is **no production data before Phase 12**, so no historical backfill is needed. Phase 8 does include a dev/test re-seeding tool.
   - The risk is that the event payloads turn out to be insufficient for posting. This is mitigated by **event contract snapshot tests** from Phases 5–7, and by Phase 8 being allowed to extend payloads additively.
   - The alternative was to build the Accounting core first. It was considered and rejected because the agreed sequence puts Accounting at Phase 8, and because the GL kernel is most valuable once real posting sources exist to validate it end-to-end.
7. **HR and Payroll after Accounting.** HR does not depend on Accounting, but Payroll's main output is a GL posting, and payroll is only verifiable end-to-end with Accounting present. HR is placed in the same phase because Payroll is its only heavy consumer. HR could be pulled earlier if the business needs it (see §4).
8. **Reporting after all data producers.** It consumes the reporting views published by all modules.
9. **Frontend after the APIs are stable.** Building the UI against a moving API wastes effort. Phases 2–10 deliver complete, tested APIs with OpenAPI. Phase 11 builds the SPA against a stable contract. A thin UI shell can optionally start after Phase 4 in parallel (§4).
10. **Production readiness last.** It audits the integrated system: security, performance, DR, operations and data migration.

---

## 3. Global definition of done (applies to every phase from 2 onwards)

A phase is done only when **all** of the following hold:

- [ ] Migrations follow DATABASE.md: naming, constraints, composite company FKs, RLS on every company-scoped table, and `CHECK`s for enumerations.
- [ ] Domain logic is in `domain` (framework-free), use cases are in `application`, and SQL is in `persistence`. Module boundaries hold: `ModularityTests` and ArchUnit are green.
- [ ] Every endpoint:
  - has exactly one of `@PublicEndpoint`, `@AuthenticatedEndpoint` or `@RequiresPermission`
  - follows API.md: errors, pagination, ETag/If-Match, Idempotency-Key where required, decimal strings
  - is in OpenAPI with `x-permission` and its error codes
- [ ] New permissions are added to SECURITY.md §4.2, the module's `Permissions` class and the system role seeds.
- [ ] Every mutation writes an audit record, and every state transition goes through the state machine.
- [ ] Tests:
  - Unit tests for domain rules and state machines (property-based tests for money, rounding, costing and balancing).
  - Integration tests on PostgreSQL through Testcontainers, covering migrations, triggers, RLS and locking.
  - `@ApplicationModuleTest` for module wiring.
  - API tests for happy paths and every documented error code.
  - The authz matrix and IDOR suites include the new endpoints.
  - Concurrency tests wherever DATABASE.md §9 lists locks.
- [ ] Coverage is ≥ 80% line coverage on `domain` and `application` packages of the phase's modules. Coverage is a floor, not a goal.
- [ ] Published events have contract snapshot tests (JSON shape).
- [ ] CI is green: build, tests, SAST, dependency scan, secret scan.
- [ ] Docs are updated. Any deviation from the specification has an ADR in DECISIONS.md, and API.md, DATABASE.md and PRODUCT_SPEC.md are corrected.
- [ ] The phase summary lists what was delivered, deviations, known gaps and the next phase's prerequisites.

---

## 4. Phases in detail

### Phase 1 — Architecture and specification ✅

**Deliverables:**

- `docs/PRODUCT_SPEC.md`, `ARCHITECTURE.md`, `DATABASE.md`, `API.md`, `SECURITY.md`, `DEVELOPMENT_PLAN.md` and `DECISIONS.md`
- root `README.md` and `CLAUDE.md`

**Exit:** the documents are reviewed, and the open questions in DECISIONS.md §3 are acknowledged. Defaults apply unless they are overridden.

### Phase 2 — Foundation (platform kernel, tooling, org core) ✅

**Goal:** a running, deployable modular monolith with the cross-cutting machinery proven by tests.

**Delivered:**

1. **Repository scaffold:**
   - `backend/`: Gradle 9.8 (Kotlin DSL, wrapper with a pinned checksum), version catalog, Java 25 toolchain, Spring Boot 4.1.1, Spring Modulith 2.1.1, dependency lockfiles, Spotless (palantir-java-format), `-Xlint:all -Werror`.
   - jOOQ code generation (`generateJooq`): starts a throwaway PostgreSQL 18.6 container, applies the role bootstrap and all Flyway migrations exactly as production does, and generates `com.erp.db.<schema>` into `build/` (not committed).
   - `frontend/` placeholder.
   - `infra/compose/docker-compose.yml` (PostgreSQL 18.6, plus an optional migrate job and API in the `app` profile).
   - `infra/docker/backend.Dockerfile` (layered, non-root, read-only-root-filesystem capable).
   - `.editorconfig`, `.gitignore`, `.gitattributes`, `.dockerignore`, `.env.example`.
   - The git repository is initialized.
2. **CI pipeline** (`.github/workflows/ci.yml`; actions pinned by SHA):
   - dependency resolution against the lockfiles
   - lint (`spotlessCheck`)
   - type check (`compileJava compileTestJava`)
   - tests
   - `bootJar`
   - image build and Trivy image scan (fail on fixable CRITICAL)
   - gitleaks over the full history
3. **DB roles and bootstrap:**
   - `infra/db/bootstrap/00-roles.sql` (roles and database grants; idempotent).
   - Migration `V202610021000__platform__bootstrap.sql`: extensions in `public`; the helpers `platform.setup_module_schema`, `platform.enable_company_rls`, `platform.current_company_id()` and `platform.current_user_id()`; grants.
4. **Platform kernel:**
   - `config`: typed `ErpProperties`, plus `RequiredConfigurationValidator`, which fails fast with the names of missing environment variables and refuses migrations on production API startup.
   - `context`: `RequestContext`, `CurrentContext`.
   - `tx`: `CompanyScopedTransactionManager`, which sets `app.company_id` and `app.user_id` per transaction and enforces read-only transactions. Statement, lock and idle-in-transaction timeouts are set through the JDBC options.
   - `web`:
     - RFC 9457 problems (`ApiProblem`, `ErrorCode`, `PlatformErrorCode`, `ApiException`, `GlobalExceptionHandler`, `/error` controller)
     - the database error translator with constraint-name mappings
     - request-ID and access-log filter
     - request body size limit
     - `EntityTags` (ETag/If-Match)
   - `web.paging` + `jooq`: list definitions, a strict parser (filters, sort, `q`, limit), HMAC-signed keyset cursors, and the keyset paginator.
   - `json`: strict Jackson 3 configuration, with decimals only as strings, string sanitizing, and no unknown properties or coercion.
   - `logging`: redaction for pattern logs (`%m`) and for structured ECS JSON logs.
   - `security`: deny-by-default filter chain, `@PublicEndpoint` / `@AuthenticatedEndpoint` / `@RequiresPermission`, `PermissionCheck` port (fail closed while no implementation exists), problem-JSON 401/403, CSRF (cookie + `X-CSRF-Token`), API security headers, actuator restricted to the management port.
5. **Org core:**
   - `org.currencies` (154, ISO 4217 in current use) and `org.countries` (249), seeded by repeatable migrations.
   - `org.companies`, and `org.branches` with RLS.
   - `GET /api/v1/reference/currencies` and `/countries` (authenticated; full list conventions).
6. **Observability:** structured ECS JSON logs (non-local profiles) with `request_id`, redaction and an access log; actuator liveness, readiness (including the DB) and Prometheus metrics on the management port; graceful shutdown.
7. **Tests:** 118 at the end of Phase 2:
   - unit tests
   - Testcontainers integration tests: clean-database migration, role privileges, RLS catalogue plus behaviour, transaction context, the API conventions end to end, permission enforcement, reference data paging, actuator over real HTTP, and the `prod,migrate` one-shot job
   - `ModularityTests` (plus generated module documentation)
   - `ArchitectureTests`

**Exit criteria (met):**

- `docker compose up postgres` plus `./gradlew bootRun --args='--spring.profiles.active=local'` migrates a clean database and serves health, readiness and the reference endpoints.
- Tests prove that RLS hides company B's rows in company A's context, rejects cross-company inserts, and shows nothing without a context, even when the application "forgets" its filter.
- The error format, validation, CSRF, ETag/If-Match and database error translation are demonstrated by API tests.
- CI runs lint, type check, tests, build and the security scans.

**Scope moved to the phase that first uses it** (ADR-025). The user's Phase 2 brief ruled out premature abstractions and fake implementations; these items have no consumer before the listed phase:

| Item (planned for Phase 2) | Moved to | First consumer |
|---|---|---|
| `POST /api/v1/companies`, company and branch CRUD endpoints, `OrgFacade` | Phase 3 | Company creation by system admins needs authentication |
| `audit`: `AuditPort` + `admin.audit_log` (partitioned) | Phase 3 | User/role administration (the first business mutations) |
| db-scheduler, worker mode (`ERP_ROLE=worker`) | Phase 3 | Purge jobs for sessions, tokens and login attempts |
| Mailpit in compose | Phase 3 | Invitation and password-reset emails |
| `idempotency`: filter, store and purge | Phase 4 | First business create endpoints |
| `events`: Event Publication Registry, `processed_events` | Phase 4 | `org.company.created` seeding pipeline |
| `crypto`: field encryption | Phase 4 | Partner bank accounts |
| `files`: S3 port, MinIO | Phase 4 | Partner/company attachments |
| `numbering`: gapless sequences (with the 50-way concurrency test) | Phase 5 | Stock movement numbers |
| Transaction retry decorator (40001/40P01) | Phase 5 | Stock locking |
| `money`, typed IDs, `time` (`BusinessCalendar`) | Phases 4–5 | Tax codes, stock values, business dates |
| OpenTelemetry tracing export | Phase 12 | Production observability backend (Q-9) |
| CodeQL/Semgrep in CI | Phase 12 or when repository hosting is decided | Requires the hosting decision (CodeQL on private repositories needs GitHub Advanced Security) |

### Phase 3 — Authentication and RBAC

**Prerequisites:** Phase 2.

**Deliverables:**

- Carried over from Phase 2 (ADR-025):
  - company and branch administration endpoints (API.md §17.3) and the `OrgFacade`
  - `AuditPort` with the partitioned `admin.audit_log`
  - db-scheduler with worker mode
  - Mailpit in compose
  - an opt-out from string trimming for password fields (`SanitizingStringDeserializer`)
  - the `Origin` check, the CSRF bootstrap endpoint and the bearer-token CSRF exemption
- Users, invitations, password set and reset (with email through Mailpit in development), Argon2id hashing, breached-password list check.
- Session login and logout with Spring Session JDBC, cookie settings, CSRF (cookie plus header, Origin check), idle and absolute timeouts, session rotation, concurrent-session cap, session listing and revocation.
- Login throttling and lockout (`auth.login_attempts`, Bucket4j/PostgreSQL), plus the general API rate limiter.
- TOTP MFA (setup, confirm, verify, recovery codes, mandatory-by-role enforcement), and step-up re-authentication.
- Service accounts and API tokens (hash, prefix, expiry, company and permission down-scoping, per-token rate limit).
- Permission catalogue sync from code, system roles seed (SECURITY.md §4.3), custom roles, role assignments with branch scoping and validity.
- A `PermissionCheck` implementation with a per-(user, company) cache and invalidation, plus `CompanyContextInterceptor` (404 masking).
- SoD policy framework: a reusable `SegregationOfDutiesPolicy`.
- Endpoints: API.md §17.1, and §17.2 (users, roles, service accounts).
- **Security test suites:** the authz matrix generator and the IDOR suite (SECURITY.md §4.5), active for every later phase.

**Exit criteria:**

- Every test in SECURITY.md §13 tagged "Phase 3" passes.
- A user without an assignment cannot see any company resources (404).
- Permission removal takes effect within 60 s.
- Disabling a user kills their sessions immediately.

### Phase 4 — Organization (complete) and Partners

**Prerequisites:** Phase 3.

**Deliverables:**

- Carried over from Phase 2 (ADR-025): idempotency (filter, store, purge), the Event Publication Registry and the `processed_events` dedup helper, field encryption (`platform.crypto`), the S3 file port with MinIO in compose, and `platform.money`.
- **Org:**
  - departments (tree, cycle guard)
  - exchange rates (with the lookup service: latest on or before a date)
  - tax codes (validity, immutability once used, through a usage port)
  - payment terms (due date calculation)
  - company settings, numbering prefixes
  - company creation seeding pipeline (the `org.company.created` async listener framework; module seeders are registered by later phases)
- **Partners:** partners, addresses, contacts, encrypted bank accounts (masking, reveal, change notification), customer and supplier profiles, partner groups, search (`pg_trgm`).
- **Admin:** company audit-log query endpoints (`GET {c}/audit-log`), entity history.
- CSV import framework (dry-run, per-row errors), used for partners here and reused later.

**Exit criteria:**

- The API tests in §17.3 and §17.4 pass.
- Bank detail changes are audited with redaction.
- Partners from company B are invisible from A (IDOR and RLS).

### Phase 5 — Inventory

**Prerequisites:** Phase 4.

**Deliverables:**

- Carried over from Phase 2 (ADR-025): gapless numbering (`platform.document_sequences`, with the 50-way concurrency test) and the transaction retry decorator.
- **Catalog:** UoM categories and units (global seed), product categories (ltree), products, attributes, variants (default variant automation, attribute-combination uniqueness), product UoM conversions with the conversion service, archive rules.
- Warehouses (seeded default locations), locations (tree, types), reason codes, inventory settings.
- **Stock engine:**
  - movements (DRAFT → POSTED → reversal)
  - the inventory ledger
  - `stock_balances` and `warehouse_stock`
  - the locking protocol (DATABASE.md §9)
  - the moving-average costing engine (INV-4/INV-5)
  - reservations (reserve, release, consume)
  - two-step transfers through TRANSIT
  - adjustments with approval threshold
  - opening stock, including CSV import
- Physical counts (snapshot, count, complete, post).
- **The `InventoryFacade` used by Procurement and Sales:** `receive`, `issue`, `returnToSupplier`, `returnFromCustomer`, `reserve`, `release`, `consumeReservation`, `availability`, `convertQuantity`, `variantInfo`.
- Publish `inventory.stock_movement.posted`, with the full payload from ARCHITECTURE.md §7 and a contract snapshot test.
- `v_rpt_*` views for inventory (DATABASE.md §11).
- Nightly invariant job for the inventory checks (ARCHITECTURE.md §6.8).

**Exit criteria:**

- Concurrency tests pass: parallel issues of one SKU never go negative, and parallel reserve and issue are consistent.
- Property-based tests: for random sequences of receipts, issues and transfers, ledger = balances = valuation, and value is never negative or left as residue when the quantity reaches 0.
- Reversal correctness is proven.

**Risks:** lock contention on hot SKUs. Mitigation: measure in Phase 12, and keep transactions short.

### Phase 6 — Procurement

**Prerequisites:** Phase 5.

**Deliverables:**

- Procurement settings.
- Requisitions (approval with SoD, conversion to POs).
- **POs:** pricing, tax computation through Org tax codes and the rounding rules (PRODUCT_SPEC.md G-14), approval thresholds, the state machine, PDF render (async), and optional email to the supplier.
- Goods receipts, through `InventoryFacade.receive`, with over-receipt tolerance and exchange rate at the receipt date.
- Purchase returns.
- **Supplier bills and debit notes:** from receipts or direct, three-way match with tolerances and override, duplicate supplier invoice detection, totals in document and base currency, `supplier_bill_taxes`.
- Publish `procurement.supplier_bill.posted` / `debit_note.posted` and `goods_receipt.posted` / `purchase_order.approved`, with contract tests.
- `InvoiceSettlementPort`-style port definitions for bill settlement, implemented in Phase 8 with a default of "unknown" until then.
- Reporting views.

**Exit criteria:**

- The full P2P flow works through the API: requisition → PO → approval → partial receipts → bill with match → debit note.
- Quantity tracking invariants hold under concurrent receipts against the same PO (the PO header lock).

### Phase 7 — Sales

**Prerequisites:** Phase 5. Phase 6 patterns are reused.

**Deliverables:**

- Sales settings.
- Price lists and the pricing engine (SAL-1), with a `POST /pricing/quote` endpoint.
- Quotations (with an expiry job).
- **Sales orders:**
  - confirmation with the credit check through `CustomerCreditExposurePort` (default zero exposure until Phase 8)
  - reservation through Inventory
  - backorders
  - cancel and close with reservation release
- Deliveries (issue consuming reservations, recording unit cost).
- Sales returns (back at the original cost).
- **Invoices and credit notes:** from orders or deliveries per invoice policy, direct invoices for services, gapless numbering, snapshots, PDF, email sending (async).
- Publish `sales.invoice.posted` / `credit_note.posted` / `order.confirmed` / `order.cancelled`, with contract tests.
- Reporting views.

**Exit criteria:**

- The full O2C flow works through the API, including partial deliveries, returns and credit notes.
- Over-delivery, over-invoicing and over-crediting are prevented under concurrency.

### Phase 8 — Accounting

**Prerequisites:** Phases 4–7.

**Deliverables:**

- Accounting settings, the CoA (template `STANDARD_SME` seeding through the company-created pipeline, plus a backfill job for existing companies), account mappings with resolution precedence, fiscal years and periods, and journals.
- **Posting engine:**
  - `PostingService` with all the database guards (DATABASE.md §8.1)
  - period checks
  - gapless journal numbering
  - rounding-difference handling
  - idempotency per source event
  - reversal service
- **Synchronous listeners** for every posting event in the PRODUCT_SPEC.md §8.6 matrix: inventory movements, supplier bills and debit notes, invoices and credit notes. Payroll events are added in Phase 9.
- **Integration tests** for every Phase 5–7 flow, now asserting the exact GL entries. They also cover rollback: a missing mapping or a closed period rolls back the operational document.
- AR/AP open items and the ports implemented for Sales and Procurement (credit exposure, settlement).
- Payments (post, void), allocations and netting, realized FX, and on-account remainders.
- Bank accounts.
- Expenses.
- Bank reconciliation marks.
- Manual journal entries with SoD.
- Period soft-close, close (snapshots), reopen, and year-end close (async job).
- **Financial reports** (PRODUCT_SPEC.md §8.10) with CSV, XLSX and PDF export.
- **Invariant jobs:** trial balance balanced, control accounts = subledgers, inventory GL = valuation.
- Optional: the batch supplier payment proposal.

**Exit criteria:**

- Property-based tests: random operational sequences always yield a balanced trial balance, AR = Σ open receivables, AP = Σ open payables, and inventory account = valuation.
- Posting during a concurrent period close is safe.
- Posted entries are provably immutable at the database level.

**Risks:** the event payloads may lack data. Mitigation: additive payload changes with the publisher's contract tests updated, and an ADR if any change is semantic.

### Phase 9 — HR and Payroll

**Prerequisites:** Phases 3, 4 and 8.

**Deliverables:**

- **HR:**
  - employees (encrypted sensitive fields, reveal with step-up and audit)
  - user linking
  - effective-dated assignments (exclusion constraint)
  - positions and department heads
  - leave types, ledger, requests with manager or HR approval, accrual job, public holidays and working-day calculation
  - termination flow (deactivating the user through the Auth facade, and an event)
  - employee documents
  - self-service endpoints
- **Payroll:**
  - components, structures, schedules, periods
  - compensations (effective-dated) and inputs
  - the calculation engine (PAY-1 to PAY-3) as an async chunked job
  - the `StatutoryRule` SPI with a "none" rule and a flat-percentage rule
  - the run state machine with SoD
  - payslips with PDF
  - post and mark-paid events, and the Accounting listeners for `payroll.run.posted` and `payroll.run.paid`
  - bank file export
- Reporting views (headcount, payroll summary).

**Exit criteria:**

- A payroll for 1,000 employees calculates in under 2 minutes.
- Proration is correct for mid-period hires and terminations.
- The GL entries match the posting matrix.
- Sensitive data is masked and its reveal is audited.
- Employees can see only their own data.

### Phase 10 — Reporting and analytics

**Prerequisites:** Phases 5–9.

**Deliverables:**

- `reporting` schema, the report catalogue and permission mapping, the reporting read-only DataSource (`erp_reporting` role, optional replica).
- All reports in PRODUCT_SPEC.md §13, including gross margin and the GRNI reconciliation.
- **The async export framework:** CSV, XLSX and PDF; CSV-injection-safe; size limits; file expiry.
- Saved reports, role dashboards and KPI endpoints.
- Report performance budgets are verified with seeded volume data at 1/5 of the target volume.

**Exit criteria:**

- Standard reports return in under 2 s at p95 on the volume dataset.
- Every report reconciles with its source: stock valuation = GL inventory, and GRNI report = GRNI account.

### Phase 11 — Frontend (web SPA)

**Prerequisites:** stable APIs from Phases 3–10.

**Deliverables:**

- **Foundation:**
  - Vite, React 19 and strict TypeScript
  - TanStack Router and Query
  - the client generated from OpenAPI
  - an auth flow covering login, MFA, CSRF bootstrap, session expiry handling and step-up dialogs
  - a company switcher
  - permission-aware navigation and actions (UX only)
  - the error-problem renderer, mapping field errors to forms
  - ETag/If-Match and Idempotency-Key handling built into the API client
  - i18n-ready strings
  - decimal handling with decimal.js and locale formatting
- **Design system:** Tailwind and shadcn/ui; data grid with server-side pagination, filters and sort; document editor pattern (header + lines + totals + state actions); master-data form pattern; audit history panel; attachments panel.
- Screens for every module, prioritized by persona workflows (PRODUCT_SPEC.md §1.3), including the warehouse screens optimized for tablets.
- Report centre and dashboards (follow the dataviz conventions).
- Accessibility to WCAG 2.2 AA, checked with axe.
- **Tests:** Vitest and Testing Library for components; Playwright end-to-end tests for the critical flows (P2P, O2C, payment allocation, period close, payroll run, leave request).

**Exit criteria:**

- The Playwright suite is green against a seeded environment.
- Axe reports no serious violations.
- The SPA security headers and CSP are verified.

**Optional parallelization:** an app shell (login, layout, company switcher) may start after Phase 4, if a separate frontend track is staffed.

### Phase 12 — Production-readiness audit

**Prerequisites:** all previous phases.

**Deliverables and audits:**

- **Security:** an ASVS L2 checklist review, OWASP ZAP baseline, an external penetration test with remediations, dependency, container and SBOM review, and a secrets audit. Revisit the CSP `unsafe-inline` styles.
- **Performance:** k6 or Gatling load tests against ARCHITECTURE.md §10 targets, including a hot-SKU contention scenario and month-end close with volume. Tune indexes and connection pools.
- **Reliability:** backup/PITR restore drill (RPO and RTO verified), failover test, and a chaos test of worker restart with incomplete event publications.
- **Operations:**
  - deployment manifests (`infra/deploy`)
  - migration step in CD
  - zero-downtime deploy rehearsal (expand/contract)
  - alert rules and dashboards (ARCHITECTURE.md §6.8)
  - runbooks: period-close failure, invariant-check failure, stuck event publications, account lockout storms, key rotation
- **Data migration toolkit:** imports for opening balances, open AR/AP, opening stock, partners, products and employees, with reconciliation reports.
- **Compliance:** confirm retention policies, the jurisdictional tax and payroll requirements, and the data-protection review (DECISIONS.md open questions resolved or risk-accepted).
- **Documentation:** a final pass on the docs, an operator guide and a user guides outline.

**Exit criteria:**

- Go/no-go checklist signed off.
- No open Critical or High findings.
- The restore drill succeeds.
- Load targets are met.

---

## 5. Working agreements for implementers (human or AI)

1. **Read before writing.** At the start of each phase, re-read `CLAUDE.md`, this plan's phase section, and the relevant sections of the other docs.
2. **The specification is normative.** If the implementation needs to differ, update the specification in the same change and add an ADR (DECISIONS.md §2). Do not let code and docs drift.
3. **Never weaken an invariant** (constraints, triggers, RLS, permissions) to make a test pass.
4. **Small, reviewable commits** per vertical slice (migration → domain → application → API → tests).
5. **Stop at the phase boundary** and report: what was delivered, deviations, known gaps and readiness of the next phase.
