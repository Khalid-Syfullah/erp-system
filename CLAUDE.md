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
./gradlew jacocoTestCoverageVerification             # 80 % line floor on domain/application packages
```

- New tables: create them in a new `V<yyyyMMddHHmm>__<module>__<desc>.sql`. Call `platform.setup_module_schema` for a new schema and `platform.enable_company_rls` for every company-scoped table. `RowLevelSecurityIntegrationTest` fails otherwise.
- jOOQ classes are generated (`com.erp.db.<schema>`) and never committed. A module uses only its own schema package (`ArchitectureTests`).
- New endpoints are covered automatically by `EndpointSecurityMatrixTest` (anonymous 401, foreign company 404, missing permission 403). New permission codes go into SECURITY.md §4.2 **and** `R__seed_auth_permissions_and_roles.sql`; `PermissionCatalogIntegrationTest` fails otherwise.
- Integration tests get users, companies, roles and logins from `AuthTestSupport` (`auth.user()`, `auth.company()`, `auth.assign(...)`, `auth.login(...)`; unsafe requests need `AuthTestSupport.unsafe(...)` for CSRF and Origin).
- Organization and HR fixtures go through the API with `OrgFixtures` (`fixtures.admin()` = a signed-in COMPANY_ADMIN + HR_MANAGER of a new company, `fixtures.department(...)`, `fixtures.employee(...)`, `OrgFixtures.json(...)`, `OrgFixtures.NULL` for merge-patch nulls).
- Inventory fixtures go through the API with `InventoryFixtures` (`inv.setup()` = a company, a user with every inventory permission, a branch, warehouse `WH1` with its seeded locations, a category, a stockable product in EA and reason codes; `inv.opening(...)`, `inv.movement(...)`, `inv.post(...)` with an Idempotency-Key, `inv.balance(...)`; `inv.inCompany(setup, () -> facade...)` calls `InventoryFacade` the way Procurement and Sales will).
- Procurement fixtures build on `InventoryFixtures` with `ProcurementFixtures` (`proc.setup()` = the inventory setup plus procurement and partner permissions, a USD supplier with net-30 terms, a 10 % purchase tax code and a separate approver session for segregation of duties; `proc.approvedOrder(...)`, `proc.postedReceipt(...)`, `proc.action(p, session, path, version, idempotencyKey, body)`, `proc.inCompany(p, () -> facade...)`).
- Sales fixtures build on `InventoryFixtures` with `SalesFixtures` (`sales.setup()` = the inventory setup plus every sales permission except the overrides, a USD customer with net-30 terms and a 10 % sales tax code, a default USD price list pricing the setup's product at 25, and a separate manager session holding `override_credit` / `override_price` / `discount_high` / `create_direct`; `sales.stock(...)`, `sales.line(...)`, `sales.confirmedOrder(...)`, `sales.postedDelivery(...)`, `sales.postedInvoice(...)`, `sales.service(...)`, `sales.action(o, session, path, version, idempotencyKey, body)`, `sales.inCompany(o, () -> ...)`).
- Accounting fixtures go through the API with `AccountingFixtures` (`acc.setup()` = a new company, seeded by `org.company.created` with the STANDARD_SME chart, mappings, journals and the current fiscal year, a user with every accounting permission (MFA enrolled), an approver and a USD bank account on GL 1010; `acc.books(company)` does the same for an inventory, procurement or sales setup's company; `acc.posted(...)`, `acc.lines(b, module, type, sourceId)` = the GL lines of a document by account code, `acc.balances(b)` / `amounts(...)` / `nonZero(...)`, `acc.periods(...)`, `acc.period(b, session, period, action, body)`, `acc.payment(...)` / `acc.postedPayment(...)`, `acc.openItem(b, "receivables"|"payables", sourceId)`). Users holding `accounting.period.reopen` or `accounting.payment.void` need MFA before login. End accounting tests with `LedgerInvariantCheck.check(company).clean()`.
- HR fixtures go through the API with `HrFixtures` (`hr.setup()` = a company with branch MAIN, department OPS, position DEV and an MFA-enrolled user holding every HR permission; `hr.employee(h, number, hireDate, managerId)` = an ACTIVE employee with an open-ended assignment; `hr.person(h, employeeId)` = a linked, signed-in user for `/me/...`; `hr.leaveType(...)`, `hr.accrue(h, asOf)`, `HrFixtures.firstMonday(...)`).
- Payroll fixtures build on `HrFixtures` and `AccountingFixtures` with `PayrollFixtures` (`payroll.setup()` = an officer and a separate approver session, components BASIC/HOUSING/OVERTIME/TAX/PENSION/PENSION_ER, structure STD and a monthly USD schedule with this year's periods; `payroll.employee(p, number, hireDate, base)`, `payroll.period(p, month)`, `payroll.run(...)`, `payroll.calculate(p, run)` runs the calculation job, `payroll.post(...)`, `payroll.payslip(p, run, employee)`). The calculation and PDF jobs run only when called (`PayrollJobs`); files go to the S3Mock test container.
- Reporting fixtures go through the API with `ReportingFixtures` (`rep.user(company, permissions...)` / `rep.user(company, branches, permissions...)` = a signed-in, MFA-enrolled user with exactly those permissions; `rep.run(session, company, code, query)` = the body of `GET {c}/reports/{code}`, `rows(...)`, `totals(...)`, `decimal(...)`, `rep.allRows(...)` following cursors; `rep.export(...)` / `rep.processExports(company)` runs the export worker / `rep.exported(...)` = the downloaded file, `ReportingFixtures.csv(bytes)`). Reports read `v_rpt_*` views as `erp_reporting`: a new view needs `security_invoker = true`, `REVOKE ALL … FROM erp_app` and grants to `erp_reporting` on the view and the columns behind it (`ReportCatalogIntegrationTest`); a new report goes into `ReportCatalog`, a `*ViewQueries` class and `R__seed_reporting_report_definitions.sql`.
- Posting endpoints marked [I] need an `Idempotency-Key` header. `OpenApiContractTest` regenerates `build/openapi/openapi.json` and fails if an operation's `x-permission` differs from its annotation.
