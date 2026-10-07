# Frontend

The ERP web application: a React 19 + TypeScript (strict) single-page application built with Vite.
It runs on the same origin as the API (`/` → SPA, `/api` → backend; ADR-023) and is generated
against the backend's OpenAPI document. Decisions and deviations: [ADR-041](../docs/DECISIONS.md).

## Commands

```bash
npm ci
npm run dev            # http://localhost:5173, proxies /api to ERP_BACKEND_URL (default http://localhost:8080)
npm run typecheck      # route tree generation + tsc
npm run lint
npm test               # Vitest (components, client, formatting, header configuration)
npm run build          # production build in dist/ (hidden source maps)
npm run preview        # serves dist/ with the production security headers on :4173
npm run api:generate   # src/api/schema.d.ts from ../backend/build/openapi/openapi.json
npm run api:check      # fails when schema.d.ts is stale (CI)
npm run test:e2e       # Playwright (needs the local stack, see below)
```

The OpenAPI document is written by the backend's `OpenApiContractTest` (part of `./gradlew build`).
Regenerate the types after an API change; a breaking change then fails `npm run typecheck`.

## Structure

| Path | Contents |
|---|---|
| `src/api/` | Generated types (`schema.d.ts`), the typed client (`client.ts`: CSRF, `If-Match`, `Idempotency-Key`, problems, step-up, session expiry), list queries, query and mutation hooks |
| `src/auth/` | Session (`GET /me`), company context and permissions, sign-in pages' pieces, MFA enrollment, step-up dialog |
| `src/components/ui/` | shadcn/ui (Radix) primitives, added with `npx shadcn@4 add <name>` (the CLI is not a dependency; its Tailwind variants are vendored in `src/styles/shadcn-tailwind.css`) |
| `src/components/` | `data/` (data table, entity pickers and names), `form/` (fields, decimal and date inputs, server-error mapping), `overlay/` (modal, drawer, confirm and form dialogs), `feedback/`, `document/` (state actions, document layout, lines editor, audit history, attachments), `master/` (master-data and settings pages), `common/` |
| `src/modules/` | Screens per module: dashboard, org, hr, inventory, procurement, sales, accounting, payroll, reports, admin, account |
| `src/routes/` | File routes (TanStack Router); `/c/$companyId/...` for company pages, `/admin/...` for system administration |
| `src/i18n/` | The typed message catalogue (English) |
| `src/lib/` | Formatting of decimal strings, amounts and dates (`Intl`, decimal.js), enums, theme |
| `e2e/` | Playwright: the seed, the critical flows, the smoke and accessibility pass, tablet screens, security headers |
| `security-headers.mjs` | The SPA security headers (SECURITY.md §10.1), used by `vite preview` and checked against `infra/docker/nginx/spa.conf` |

## Conventions

- The backend decides: permissions only hide navigation and actions, prices, taxes and totals are the
  server's, and every write invalidates the company's cached data. No optimistic UI for documents.
- Every visible string comes from `src/i18n/en.ts`; unknown enum values display humanized.
- Money and quantities stay decimal strings (`formatMoney`, `formatDecimal`, `DecimalInput`); `parseFloat` is banned by lint.
- `dangerouslySetInnerHTML` is banned by lint; the CSP allows scripts from the origin only.
- Referenced records are shown with `EntityName`: 'all' sources load once per company, 'search' sources are
  resolved in batches (`filter[id][in]`, one request per 100 IDs), never one request per cell.
- New screens: lists use `DataTable` (server filters and sorts must be on the endpoint's allowlist),
  master data `MasterDataPage`, documents `DocumentLayout` + `DocumentActions` (+ `LinesEditor` for drafts),
  action inputs `FormDialog`. Forms use react-hook-form with the `zf` schema helpers and `useSubmit`.

## End-to-end tests

The suite runs against the local stack: PostgreSQL, Mailpit and object storage from
`infra/compose/docker-compose.yml`, and the backend with the `local` profile and
`ERP_SECURITY_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:4173,http://localhost:8080`.
A system administrator must exist (`bootstrap-admin`, README of the repository); the seed uses
`admin@erp.local` / `Correct-Horse-Battery-77` unless `ERP_ADMIN_EMAIL` / `ERP_ADMIN_PASSWORD` say otherwise.

```bash
npm run test:e2e                                   # seeds the DEMO company, then runs every spec
node --experimental-strip-types e2e/support/seed.ts # seeds only (users alice, bob, erin; e2e/.state/)

# The security headers and CSP against a production build:
npm run build && npm run preview &
ERP_SPA_PRODUCTION=1 ERP_SPA_URL=http://localhost:4173 npx playwright test e2e/security-headers.spec.ts
# … or against the web image (docker compose --profile app up web), adding ERP_SPA_IMAGE=1.
```

The seed signs users in once and stores their cookies (`e2e/.state/`, git-ignored, with the TOTP
secrets): sign-in is rate limited per account and IP.
