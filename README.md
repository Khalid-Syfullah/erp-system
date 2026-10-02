# ERP

A production-grade ERP for medium-sized organizations. It is built as a **modular monolith**, with a Java 25 / Spring Boot 4.1 / Spring Modulith / jOOQ backend on PostgreSQL 18, and a React + TypeScript SPA (Phase 11).

**Status:** Phase 2 (foundation) is complete. The platform kernel, the database role model with migrations and row-level security, the error model and list conventions, the security skeleton, the org core and reference data, tests, Docker and CI are in place. Authentication arrives in Phase 3. Until then every non-public API endpoint answers `401`.

## Documentation

| Document | Contents |
|---|---|
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

# PostgreSQL 18 with the ERP roles (first start runs infra/db/bootstrap/00-roles.sql)
docker compose --env-file .env -f infra/compose/docker-compose.yml up -d postgres

# Run the API with the "local" profile: migrates on startup, human-readable logs
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

- API: <http://localhost:8080/api/v1> (for example `/api/v1/reference/currencies`, which returns 401 until Phase 3).
- Health: <http://localhost:8081/actuator/health/readiness> (management port, internal only).
- Metrics: <http://localhost:8081/actuator/prometheus>.

If port 5432 is taken, set `ERP_DB_PORT` and the port in `ERP_DB_URL` in `.env`, for example to `5433`.

## Build, lint, test

```bash
cd backend
./gradlew spotlessCheck                 # lint (formatting); ./gradlew spotlessApply fixes it
./gradlew compileJava compileTestJava   # type check (-Xlint:all -Werror), includes jOOQ code generation
./gradlew test                          # unit + integration (Testcontainers) + architecture tests
./gradlew build                         # all of the above + bootJar → build/libs/erp-backend.jar
```

Dependencies are locked (`gradle.lockfile`). After changing `gradle/libs.versions.toml`, run `./gradlew dependencies --write-locks`.

## Production-like run (container image)

```bash
(cd backend && ./gradlew bootJar)
docker compose --env-file .env -f infra/compose/docker-compose.yml --profile app up --build
```

The `migrate` service runs the image with `SPRING_PROFILES_ACTIVE=prod,migrate`: it applies Flyway migrations as `erp_migrator` and exits. The `app` service then starts the API with the `prod` profile (ECS JSON logs; `ERP_API_CURSOR_SIGNING_KEY` required). In production, migrations always run as that separate step (ARCHITECTURE.md §8.2).

## Configuration

All configuration comes from environment variables. Secrets have no defaults, and startup fails with the names of any missing variables.

| Variable | Purpose |
|---|---|
| `ERP_DB_URL` | JDBC URL, e.g. `jdbc:postgresql://db:5432/erp?sslmode=verify-full` |
| `ERP_DB_APP_USER` / `ERP_DB_APP_PASSWORD` | Runtime role (default user `erp_app`) |
| `ERP_DB_MIGRATOR_USER` / `ERP_DB_MIGRATOR_PASSWORD` | Migration role (only where migrations run) |
| `ERP_DB_MIGRATE_ON_STARTUP` | `true` in `local`/`test`; forbidden in `prod` (use the `migrate` profile) |
| `ERP_API_CURSOR_SIGNING_KEY` | Base64 key of at least 32 bytes for pagination cursors (required in `prod`) |
| `ERP_DB_POOL_SIZE`, `ERP_DB_STATEMENT_TIMEOUT`, `ERP_DB_LOCK_TIMEOUT` | Tuning (defaults 10, 30s, 5s) |
| `ERP_HTTP_PORT`, `ERP_MANAGEMENT_PORT` | Ports (defaults 8080, 8081) |

## Repository layout

```
backend/   Spring Boot modular monolith (com.erp.platform kernel, com.erp.<module>)
frontend/  React SPA (Phase 11)
infra/     db/bootstrap (roles), compose (local stack), docker (image)
docs/      specification
.github/   CI
```
