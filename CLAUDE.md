# Instructions for AI coding agents

The specification in `docs/` is **normative**. Read the relevant documents before changing anything:

- `docs/DEVELOPMENT_PLAN.md`: what the current phase includes, plus the global Definition of Done (§3). **Work one phase at a time, and stop at the phase boundary.**
- `docs/ARCHITECTURE.md`: module layout (§3.1), allowed dependencies (§5.2), transactions and lock order (§6.2), event catalogue (§7).
- `docs/DATABASE.md`: naming, types, RLS, composite company FKs, triggers, locking catalogue.
- `docs/API.md`: error format, pagination, If-Match and Idempotency-Key, decimal strings, endpoint catalogue.
- `docs/SECURITY.md`: permission catalogue (§4.2), enforcement layers (§4.4), sensitive data handling.
- `docs/DECISIONS.md`: ADRs and open questions with their defaults.

## Non-negotiable rules

1. Never use `float`, `double` or the `real`/`money` SQL types for money or quantities. Use `BigDecimal` and `numeric`. JSON carries decimals as strings.
2. A module may import only other modules' `api` and `events` packages, and only along the allowed dependency graph. It never touches another module's schema.
3. Every endpoint has exactly one of `@PublicEndpoint`, `@AuthenticatedEndpoint` or `@RequiresPermission`. Every query is company-scoped. Out-of-scope resources return 404.
4. Posted ledgers (journal entries, inventory transactions, audit log) are never updated or deleted. Corrections are reversals.
5. Change state only through the state machine. Every mutation writes an audit record in the same transaction.
6. Never weaken a constraint, trigger, RLS policy or permission check to make a test pass.
7. If the implementation must differ from the specification, update the documents in the same change and add an ADR to `docs/DECISIONS.md`.

## Commands (from `backend/`)

```bash
./gradlew spotlessApply      # format (CI runs spotlessCheck)
./gradlew build              # codegen (needs Docker) + compile (-Werror) + all tests + bootJar
./gradlew test --tests 'com.erp.ArchitectureTests'   # a single test class
./gradlew dependencies --write-locks                 # after changing gradle/libs.versions.toml
```

- New tables: create them in a new `V<yyyyMMddHHmm>__<module>__<desc>.sql`. Call `platform.setup_module_schema` for a new schema and `platform.enable_company_rls` for every company-scoped table. `RowLevelSecurityIntegrationTest` fails otherwise.
- jOOQ classes are generated (`com.erp.db.<schema>`) and never committed. A module uses only its own schema package (`ArchitectureTests`).
