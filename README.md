# ERP

A production-grade ERP for medium-sized organizations. It is built as a **modular monolith**, with a Java 25 / Spring Boot 4.1 / Spring Modulith / jOOQ backend on PostgreSQL 18, and a React + TypeScript SPA (Phase 11).

> **ভাষা / Language:** the web application opens in **Bangla** (বাংলা); switch to English with **বাংলা | English** at the top right. Your choice is remembered ([localization](docs/LOCALIZATION.md)).

> **New here?** The [user manual](docs/USER_MANUAL.md) explains step by step how to run the ERP: the demo company with screenshots of every business process, setting up your own company, the Docker images, and troubleshooting.

## সহজ বাংলায়: কোন অংশ কী করে

বাঁ পাশের মেনুর প্রতিটি অংশের কাজ এক নজরে:

- **ড্যাশবোর্ড:** আপনার কাজের মূল হিসাব এক জায়গায় দেখায়। যেমন কত টাকা পাওনা, কত দিতে হবে, মজুতের মূল্য আর জনবল।
- **প্রতিষ্ঠান:** কোম্পানি, শাখা, বিভাগ, গ্রাহক ও সরবরাহকারী, কর কোড আর পরিশোধের শর্ত এখানে ঠিক করা হয়।
- **মানবসম্পদ:** কর্মচারীর তথ্য, পদ, ছুটির আবেদন ও অনুমোদন, সরকারি ছুটি আর উপস্থিতি।
- **স্ব-সেবা:** কর্মচারী নিজেই নিজের প্রোফাইল, ছুটি, উপস্থিতি আর বেতন স্লিপ দেখেন।
- **মজুত:** পণ্য, গুদাম, কোথায় কত মাল আছে, মাল আনা-নেওয়া, গণনা আর মজুতের মূল্য।
- **ক্রয়:** চাহিদাপত্র থেকে ক্রয় আদেশ, মাল গ্রহণ, ফেরত আর সরবরাহকারীর বিল।
- **বিক্রয়:** মূল্য তালিকা, কোটেশন, বিক্রয় আদেশ, ডেলিভারি, ফেরত আর গ্রাহকের ইনভয়েস।
- **হিসাবরক্ষণ:** হিসাব তালিকা, জাবেদা দাখিলা, পাওনা ও দেনা, ব্যাংক, পেমেন্ট, ব্যয় আর হিসাবকাল বন্ধ করা। কেনাবেচার হিসাব এখানে নিজে থেকেই লেখা হয়।
- **বেতন ব্যবস্থাপনা:** মাসিক বেতন হিসাব করা, অন্য একজনের অনুমোদন, হিসাবে তোলা আর পরিশোধ। বেতন স্লিপ ও ব্যাংক ফাইলও এখান থেকে।
- **প্রতিবেদন:** বিক্রয়, ক্রয়, মজুত, হিসাব ও বেতনের তৈরি প্রতিবেদন, যেমন রেওয়ামিল, আয় বিবরণী আর উদ্বৃত্তপত্র। CSV, Excel ও PDF-এ নামানো যায়।
- **প্রশাসন:** ব্যবহারকারী, তাঁদের ভূমিকা ও অনুমতি, আর কে কখন কী বদলেছে তার নিরীক্ষা লগ।

যিনি কাগজ তৈরি করেন, তিনি নিজে তা অনুমোদন করতে পারেন না। আর প্রত্যেকে কেবল নিজের অনুমতির অংশটুকুই দেখেন। বিস্তারিত ধাপগুলো [ব্যবহার নির্দেশিকায়](docs/USER_MANUAL.md) আছে।

## Screenshots

**বাংলা (default language).** The web application opens in Bangla. English is one click away.

<table>
  <tr>
    <td width="50%"><a href="docs/USER_MANUAL.md#step-9-the-bangla-interface-বাংলা"><img src="docs/manual/images/bn-02-dashboard.png" alt="The dashboard in Bangla"></a><br><sub>ড্যাশবোর্ড: the dashboard in Bangla, with Bengali digits</sub></td>
    <td width="50%"><a href="docs/USER_MANUAL.md#step-9-the-bangla-interface-বাংলা"><img src="docs/manual/images/bn-04-invoice.png" alt="A posted invoice in Bangla"></a><br><sub>ইনভয়েস: a posted invoice. Document numbers and codes are unchanged.</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/USER_MANUAL.md#step-9-the-bangla-interface-বাংলা"><img src="docs/manual/images/bn-09-trial-balance.png" alt="The trial balance in Bangla"></a><br><sub>রেওয়ামিল: the trial balance</sub></td>
    <td width="50%"><a href="docs/USER_MANUAL.md#step-9-the-bangla-interface-বাংলা"><img src="docs/manual/images/bn-10-payroll-run.png" alt="A payroll run in Bangla"></a><br><sub>বেতন প্রক্রিয়া: a paid payroll run</sub></td>
  </tr>
</table>

**English.**

![The dashboard of the demo company: key figures for the signed-in user's roles, and the role-aware navigation](docs/manual/images/03-dashboard.png)

<table>
  <tr>
    <td width="50%"><a href="docs/USER_MANUAL.md#a2-buying-purchase-order--goods-receipt--supplier-bill"><img src="docs/manual/images/10-purchase-order-new.png" alt="Purchase order: supplier, warehouse and lines; totals and taxes come from the server"></a><br><sub>Purchase order: supplier, warehouse and lines; totals and taxes come from the server</sub></td>
    <td width="50%"><a href="docs/USER_MANUAL.md#a3-selling-sales-order--delivery--invoice"><img src="docs/manual/images/22-sales-order-credit-check.png" alt="Confirming a sales order: credit check and stock reservation"></a><br><sub>Confirming a sales order: credit check and stock reservation</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/USER_MANUAL.md#a3-selling-sales-order--delivery--invoice"><img src="docs/manual/images/27-invoice-posted.png" alt="A posted customer invoice with its open amount"></a><br><sub>A posted customer invoice with its open amount</sub></td>
    <td width="50%"><a href="docs/USER_MANUAL.md#a4-receiving-the-customers-payment"><img src="docs/manual/images/31-payment-allocate.png" alt="Allocating a customer payment to open invoices"></a><br><sub>Allocating a customer payment to open invoices</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/USER_MANUAL.md#a6-payroll-run--calculate--approve--post--pay"><img src="docs/manual/images/53-payroll-run-paid.png" alt="A payroll run: calculated, approved by a second person, posted and paid"></a><br><sub>A payroll run: calculated, approved by a second person, posted and paid</sub></td>
    <td width="50%"><a href="docs/USER_MANUAL.md#a7-reports-and-dashboards"><img src="docs/manual/images/62-report-trial-balance.png" alt="Report centre: the trial balance"></a><br><sub>Report centre: the trial balance</sub></td>
  </tr>
</table>

More screens, step by step, in the [user manual](docs/USER_MANUAL.md).

**Status:** Phase 11 (the web application) is complete, on top of reporting and analytics (Phase 10), HR and payroll (Phase 9), accounting (Phase 8), sales (Phase 7), procurement (Phase 6), inventory (Phase 5), organization management (Phase 4) and authentication and RBAC (Phase 3):

- the web application: a React + TypeScript SPA on the same origin as the API, typed from the OpenAPI document — sign-in with TOTP, step-up and session expiry; company switcher and role-aware navigation; screens for organization, partners, HR and self-service, inventory (with tablet count, receipt and delivery screens), procurement, sales, accounting, payroll, the report centre and dashboards, and system and company administration; server-side lists, document pages with state actions and audit history; WCAG 2.2 AA checked with axe; Playwright flows (procure-to-pay, order-to-cash, payment allocation, period close, payroll run, leave request) against a seeded stack; the SPA's security headers and CSP verified on the nginx web image
- reporting: a catalogue of 36 reports (sales, procurement, inventory, accounting, HR and payroll) built on the modules' published `v_rpt_*` views and on Accounting's own statements, read through a read-only `erp_reporting` connection (optionally a replica) under the same company, branch and permission rules; keyset-paginated results with totals; asynchronous CSV, XLSX and PDF exports streamed to object storage; saved and shared report parameters; role dashboards with KPIs; reports verified against the general ledger and within 2 s on 1/5 of the target data volume
- HR: employee records with field-encrypted sensitive data (masked, audited reveal), bank accounts, documents (S3-compatible storage), the self-service user link and termination flow; leave types, accruals with carry-forward, requests in working days with public holidays, approval by HR or the manager, balances and history; basic attendance with self-service clock-in/out; a manager's team view and headcount
- payroll: components with pluggable statutory rules (country packs add rules without touching the engine), salary structures, schedules and periods, effective-dated compensations and inputs; a deterministic, prorating calculation engine run as a background job; runs with segregation of duties, posting and payment booked in the general ledger; payslip PDFs, self-service payslips, the bank file and payroll reports

- accounting: the chart of accounts (seeded per company), account mappings, fiscal years and periods with soft close, close, reopen and the year-end close; manual journal entries with segregation of duties and reversal; the posting engine that books every inventory, procurement and sales document atomically with it; AR/AP open items, customer receipts and supplier payments with allocations, netting, realized FX and void; expenses, bank accounts and reconciliation marks; trial balance, general ledger, journal, P&L, balance sheet, ageing, statements, tax summary and cash book as JSON
- the ledger's rules enforced twice, in the application and in PostgreSQL: balanced entries (deferred check at commit), immutable posted entries and lines, no postings into closed periods, no manual lines on control accounts, one entry per source event; a daily invariant check (trial balance, AR/AP = open items, inventory GL = valuation)
- partners: customers and suppliers with addresses, contacts, groups, field-encrypted bank accounts, customer profiles (credit limit, hold) and supplier profiles
- order-to-cash: price lists with quantity tiers, quotations, sales orders with server-side pricing, the credit check and stock reservation, deliveries out of inventory, sales returns, invoices and credit notes
- procure-to-pay: requisitions with approval and conversion, purchase orders with threshold approval, goods receipts, purchase returns, supplier bills and debit notes with the three-way match
- products, warehouses and the stock engine (Phase 5), gapless document numbering, idempotent posting (`Idempotency-Key`), and an OpenAPI document generated by the build (`backend/build/openapi/openapi.json`)
- company and branch isolation, RBAC on every endpoint, segregation of duties and override permissions, and audit records for every change

Phase 12 (production readiness) has started: the code audit and its fixes are done (ADR-042), and the web application is localized: Bangla by default, English as the secondary language (ADR-043); the operational deliverables are open (see [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)).

## Documentation

| Document | Contents |
|---|---|
| [docs/USER_MANUAL.md](docs/USER_MANUAL.md) | How to run it: the demo (with screenshots), your own set-up, Docker images, checks, troubleshooting |
| [docs/LOCALIZATION.md](docs/LOCALIZATION.md) | Bangla (default) and English: preference, formatting (৳, Bengali digits), glossary, adding translations |
| [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md) | Scope, personas, business rules, document lifecycles, posting matrix |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Stack, module boundaries, communication, transactions, events, observability, deployment |
| [docs/DATABASE.md](docs/DATABASE.md) | Conventions, roles, migrations, RLS, logical model, invariants, locking |
| [docs/API.md](docs/API.md) | REST conventions, errors, pagination, concurrency, idempotency, endpoint catalogue |
| [docs/SECURITY.md](docs/SECURITY.md) | Authentication, RBAC and permission catalogue, tenant isolation, data protection, audit |
| [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) | Phases 1–12 with dependencies and exit criteria |
| [docs/DECISIONS.md](docs/DECISIONS.md) | ADRs, resolved requirement conflicts, open questions |

## Prerequisites

- JDK 25. The Gradle toolchain uses it; Gradle itself comes from the wrapper.
- Docker. It is needed for the build itself, because jOOQ code generation and the integration tests start PostgreSQL through Testcontainers, and for local infrastructure.

## Local development

```bash
cp .env.example .env              # then replace every change-me value (never commit .env)

# PostgreSQL 18 with the ERP roles (first start runs infra/db/bootstrap/00-roles.sql),
# and Mailpit for invitation and password-reset emails (UI: http://localhost:8025)
docker compose --env-file .env -f infra/compose/docker-compose.yml up -d postgres mailpit s3

# Run the API with the "local" profile: migrates on startup, human-readable logs
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'

# Once: create the first system administrator (the command exits when done; idempotent)
ERP_BOOTSTRAP_ADMIN_EMAIL=admin@example.com ERP_BOOTSTRAP_ADMIN_PASSWORD='<12+ characters, not a common password>' \
  ./gradlew bootRun --args='--spring.profiles.active=local,bootstrap-admin'
```

- API: <http://localhost:8080/api/v1>. Sign in with `GET /api/v1/auth/csrf`, then `POST /api/v1/auth/login` with the `X-CSRF-Token` header and an `Origin` from `ERP_SECURITY_ALLOWED_ORIGINS` (API.md §12). System administrators must enroll TOTP at the first login (`/api/v1/me/mfa/totp/setup` and `/confirm`).
- Locally, cookies are not `Secure` and the session cookie is `erp_session` (no `__Host-` prefix), so plain `http://localhost` works.
- Without `ERP_FIELD_ENCRYPTION_KEYS`, the local profile uses an ephemeral key, and MFA enrollments do not survive a restart. Set a key in `.env` to keep them.
- Health: <http://localhost:8081/actuator/health/readiness> (management port, internal only).
- Metrics: <http://localhost:8081/actuator/prometheus>.

### Web application

```bash
cd frontend
npm ci
npm run dev    # http://localhost:5173 (proxies /api to the backend on :8080)
```

Sign in with the bootstrapped administrator, or seed a demo company with users for every persona
(`node --experimental-strip-types e2e/support/seed.ts`; credentials in `frontend/e2e/.state/seed.json`).
The backend's `ERP_SECURITY_ALLOWED_ORIGINS` must include the SPA's origin. `docker compose … --profile app up`
also builds the web image (`web`, <http://localhost:8088>): nginx with the SPA's security headers and `/api`
proxied to the backend. More in [frontend/README.md](frontend/README.md).

A database volume created before Phase 10 has no password for the reporting role: run `ALTER ROLE erp_reporting PASSWORD '…'` (the value of `ERP_DB_REPORTING_PASSWORD`) as the superuser once, or recreate the volume.

If port 5432 is taken, set `ERP_DB_PORT` and the port in `ERP_DB_URL` in `.env`, for example to `5433`.

## Build, lint, test

```bash
cd backend
./gradlew spotlessCheck                 # lint (formatting); ./gradlew spotlessApply fixes it
./gradlew compileJava compileTestJava   # type check (-Xlint:all -Werror), includes jOOQ code generation
./gradlew test                          # unit + integration (Testcontainers) + architecture tests
./gradlew jacocoTestCoverageVerification  # >= 80 % lines in domain/application packages (report: build/reports/jacoco)
./gradlew build                         # all of the above + bootJar → build/libs/erp-backend.jar
```

Dependencies are locked (`gradle.lockfile`). After changing `gradle/libs.versions.toml`, run `./gradlew dependencies --write-locks`.

## Production-like run (container image)

```bash
(cd backend && ./gradlew bootJar)
docker compose --env-file .env -f infra/compose/docker-compose.yml --profile app up --build
```

The `migrate` service runs the image with `SPRING_PROFILES_ACTIVE=prod,migrate`: it applies Flyway migrations as `erp_migrator` and exits. The `app` service then starts the API with the `prod` profile (ECS JSON logs; `ERP_API_CURSOR_SIGNING_KEY` and `ERP_FIELD_ENCRYPTION_KEYS` required) and sends email through Mailpit. In production, migrations always run as that separate step (ARCHITECTURE.md §8.2). The first administrator is created with `docker compose … --profile app run --rm bootstrap-admin`.

## Configuration

All configuration comes from environment variables. Secrets have no defaults, and startup fails with the names of any missing variables.

| Variable | Purpose |
|---|---|
| `ERP_DB_URL` | JDBC URL, e.g. `jdbc:postgresql://db:5432/erp?sslmode=verify-full` |
| `ERP_DB_APP_USER` / `ERP_DB_APP_PASSWORD` | Runtime role (default user `erp_app`) |
| `ERP_DB_REPORTING_USER` / `ERP_DB_REPORTING_PASSWORD` | Read-only reporting role (default user `erp_reporting`; password required in `prod`); `ERP_DB_REPORTING_URL` points it at a read replica (default: `ERP_DB_URL`), `ERP_DB_REPORTING_POOL_SIZE` (default 5) |
| `ERP_DB_MIGRATOR_USER` / `ERP_DB_MIGRATOR_PASSWORD` | Migration role (only where migrations run) |
| `ERP_DB_MIGRATE_ON_STARTUP` | `true` in `local`/`test`; forbidden in `prod` (use the `migrate` profile) |
| `ERP_API_CURSOR_SIGNING_KEY` | Base64 key of at least 32 bytes for pagination cursors (required in `prod`) |
| `ERP_SECURITY_ALLOWED_ORIGINS` | Exact origins of the web app, comma-separated; checked on unsafe cookie requests (required in `prod`) |
| `ERP_FIELD_ENCRYPTION_KEYS` | `<version>:<base64 32 bytes>[,...]` AES-256-GCM keys for MFA secrets; highest version encrypts (required in `prod`) |
| `ERP_AUTH_PUBLIC_BASE_URL` | Base URL of the web app for invitation and reset links (required in `prod`) |
| `ERP_MAIL_HOST`, `ERP_MAIL_PORT`, `ERP_MAIL_USERNAME`, `ERP_MAIL_PASSWORD`, `ERP_MAIL_STARTTLS`, `ERP_MAIL_FROM` | SMTP relay (host required in `prod`; defaults to Mailpit in `local`) |
| `ERP_JOBS_ENABLED` | `true` on the worker instance(s): audit partitions, security-record purges, payroll calculation and payslip PDFs, report exports and their expiry (default `false`; `true` in `local`) |
| `ERP_BOOTSTRAP_ADMIN_EMAIL`, `ERP_BOOTSTRAP_ADMIN_PASSWORD`, `ERP_BOOTSTRAP_ADMIN_DISPLAY_NAME` | Only for the one-shot `bootstrap-admin` profile |
| `ERP_MFA_ISSUER` | Issuer shown in authenticator apps (default `ERP`) |
| `ERP_DB_POOL_SIZE`, `ERP_DB_STATEMENT_TIMEOUT`, `ERP_DB_LOCK_TIMEOUT` | Tuning (defaults 10, 30s, 5s) |
| `ERP_HTTP_PORT`, `ERP_MANAGEMENT_PORT` | Ports (defaults 8080, 8081) |

## Repository layout

```
erp-claude/
├── README.md                     this file
├── CLAUDE.md                     rules for AI coding agents
├── .env.example                  local configuration template (copy to .env)
├── .github/workflows/ci.yml      CI: backend and frontend builds with image scans, API contract, dependency and secret scans
│
├── docs/                         the specification (normative) and the guides
│   ├── PRODUCT_SPEC.md           scope, business rules, document lifecycles, posting matrix
│   ├── ARCHITECTURE.md           modules, dependencies, transactions, events, deployment
│   ├── DATABASE.md               schemas, RLS, invariants, locking
│   ├── API.md                    REST conventions and the endpoint catalogue
│   ├── SECURITY.md               authentication, permissions, data protection, audit
│   ├── DEVELOPMENT_PLAN.md       phases 1–12 and the Definition of Done
│   ├── DECISIONS.md              ADRs and open questions
│   ├── LOCALIZATION.md           Bangla and English: preference, formatting, glossary
│   ├── USER_MANUAL.md            how to run and use it, step by step
│   └── manual/images/            the manual's screenshots (English, and Bangla bn-*.png)
│
├── infra/
│   ├── compose/                  local stack: PostgreSQL, SeaweedFS (S3), Mailpit
│   ├── db/bootstrap/             database roles (owner, migrator, app, reporting, support)
│   └── docker/                   backend and frontend images, nginx config of the SPA
│
├── backend/                      Java 25 · Spring Boot 4.1 · Spring Modulith · jOOQ
│   ├── build.gradle.kts          build, jOOQ codegen, Spotless, JaCoCo (with *.lockfile)
│   └── src/
│       ├── main/java/com/erp/
│       │   ├── ErpApplication.java
│       │   ├── platform/         shared kernel: security, web, json, money, tx, audit,
│       │   │                     idempotency, numbering, files, crypto, events, jooq
│       │   ├── auth/             users, sign-in, MFA, sessions, roles and permissions
│       │   ├── org/              companies, branches, departments, tax codes, payment terms,
│       │   │                     exchange rates, document numbering
│       │   ├── partners/         customers and suppliers
│       │   ├── hr/               employees, leave, attendance, self-service
│       │   ├── inventory/        products, warehouses, the stock engine
│       │   ├── procurement/      requisitions, purchase orders, receipts, supplier bills
│       │   ├── sales/            price lists, quotations, orders, deliveries, invoices
│       │   ├── accounting/       chart of accounts, journals, posting engine, AR/AP, payments
│       │   ├── payroll/          components, structures, runs, payslips, bank file
│       │   ├── reporting/        report catalogue, dashboards, exports
│       │   └── admin/            audit log storage and queries
│       │       └── each module:  api/ events/ (public) · application/ domain/
│       │                         persistence/ web/ (internal)
│       ├── main/resources/
│       │   ├── application*.yml  configuration and profiles (local, prod, migrate, …)
│       │   └── db/migration/     Flyway migrations V<timestamp>__<module>__<desc>.sql
│       └── test/java/com/erp/    unit and integration tests per module (Testcontainers),
│                                 ArchitectureTests, ModularityTests, support/ (fixtures)
│
└── frontend/                     React 19 · TypeScript · Vite · TanStack Router/Query
    ├── package.json              scripts: dev, build, test, lint, typecheck, api:*, demo:code
    ├── index.html
    ├── security-headers.mjs      the SPA's security headers and CSP
    ├── scripts/                  API type generation, route generation, demo TOTP codes
    ├── src/
    │   ├── main.tsx · app/       entry point, router, query client
    │   ├── api/                  generated API types and the typed client
    │   ├── auth/                 session, company context, permissions, language switch
    │   ├── i18n/                 message catalogues: bn.ts (Bangla, default), en.ts (English)
    │   ├── layout/               application shell: navigation, header, user menu
    │   ├── components/           ui/ (shadcn), data/, form/, overlay/, document/, …
    │   ├── modules/              screens: dashboard, org, hr, inventory, procurement,
    │   │                         sales, accounting, payroll, reports, admin, account
    │   ├── routes/               file-based routes (/c/$companyId/... per company)
    │   ├── lib/                  number, amount and date formatting, enums
    │   └── styles/ · index.css   Tailwind and theme
    ├── e2e/                      Playwright: business flows, smoke + accessibility,
    │   │                         localization, tablet, security headers
    │   ├── support/              seed data, fixtures, helpers
    │   └── manual/               screenshot capture for docs/USER_MANUAL.md
    └── tests/                    unit test of the security headers
```

Not committed (generated or local): `.env`, `backend/build/` (including the generated jOOQ classes), `frontend/node_modules/`, `frontend/dist/` and `frontend/src/routeTree.gen.ts`. `frontend/src/api/schema.d.ts` is committed and regenerated from the backend's OpenAPI document with `npm run api:generate`.
