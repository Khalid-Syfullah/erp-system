# User manual: running the ERP

This manual explains, step by step, how to run the ERP on your own computer:

- **[Part A: the demo](#part-a-run-the-demo).** A ready-made company, "Demo Trading", with users, products, a supplier, a customer, employees and payroll. It is the fastest way to see the whole system working, and each business process is shown with screenshots.
- **[Part B: your own setup](#part-b-set-up-your-own-company).** Starting from an empty system: your administrator, your company, your users and your master data.
- **[Part C: other ways to run it](#part-c-other-ways-to-run-and-check-it).** The packaged Docker images, the automated checks, stopping and resetting.
- **[Troubleshooting](#troubleshooting).**

All commands are run in a terminal from the repository root (the folder that contains `backend/`, `frontend/` and `infra/`) unless a step says otherwise.

---

## How the pieces fit together

When you run the ERP locally, five things run side by side:

| Piece | What it does | Address |
|---|---|---|
| **Web application** (React, Vite dev server) | The screens you use in the browser | <http://localhost:5173> |
| **Backend API** (Java, Spring Boot) | All business rules, security and data access | <http://localhost:8080/api/v1> |
| **PostgreSQL** (Docker) | The database | `localhost:5432` (configurable) |
| **Mailpit** (Docker) | Catches the e-mails the system sends (invitations, password resets) so you can read them | <http://localhost:8025> |
| **S3-compatible storage** (Docker) | Stores files: employee documents, payslip PDFs, report exports | `localhost:8333` |

The browser only talks to the web application. The web application forwards every `/api` request to the backend, so both appear under one address (the same as in production).

---

## Prerequisites

Install these once:

| Tool | Version | Why | Check |
|---|---|---|---|
| **Docker Desktop** (or Docker Engine) | recent | Runs PostgreSQL, Mailpit and file storage; the backend build also uses it | `docker version` |
| **JDK** | 25 | Runs the backend (Gradle itself is downloaded automatically) | `java -version` |
| **Node.js** | 22 or newer | Runs the web application | `node --version` |
| **Git** | any | To get the code | `git --version` |

Also make sure that ports **5173, 8080, 8081, 8025, 1025, 8333** and **5432** are free. If something already uses port 5432 (another PostgreSQL), see [Troubleshooting](#port-5432-is-already-in-use).

> **Two-step verification.** Administrators and users with sensitive permissions must sign in with a password **and** a 6-digit code from an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password, …). For the demo there is a helper that prints the codes, so you don't need a phone.

---

# Part A: run the demo

### Step 1: Create your configuration file

The configuration (database passwords, encryption keys) lives in a file called `.env` that is never committed.

```bash
cp .env.example .env
```

Open `.env` in an editor and change it as follows:

1. Replace every value that starts with `change-me` with a password of your choice (any long random text, for example the output of `openssl rand -hex 20`).
2. Set the two keys below. Generate them with the commands shown and paste the results:

   ```bash
   openssl rand -base64 48                 # → ERP_API_CURSOR_SIGNING_KEY=<paste>
   echo "1:$(openssl rand -base64 32)"      # → ERP_FIELD_ENCRYPTION_KEYS=<paste>
   ```

   `ERP_FIELD_ENCRYPTION_KEYS` encrypts the two-step verification secrets. **Set it before the first sign-in**: without it, a temporary key is used, and every two-step enrollment becomes invalid when the backend restarts.

3. Add the first administrator's sign-in details at the end of the file. The demo seed (step 6) signs in with exactly these, so keep them unless you also pass `ERP_ADMIN_EMAIL` / `ERP_ADMIN_PASSWORD` to the seed:

   ```bash
   ERP_BOOTSTRAP_ADMIN_EMAIL=admin@erp.local
   ERP_BOOTSTRAP_ADMIN_PASSWORD=Correct-Horse-Battery-77
   ERP_BOOTSTRAP_ADMIN_DISPLAY_NAME=System Administrator
   ```

   Passwords need at least 12 characters, must not be a commonly used password and must not contain the user's name or e-mail.

### Step 2: Start the database, mail catcher and file storage

```bash
docker compose --env-file .env -f infra/compose/docker-compose.yml up -d postgres mailpit s3
```

The first start creates the database and its roles. Wait until it is ready:

```bash
docker compose --env-file .env -f infra/compose/docker-compose.yml ps
```

`postgres` and `mailpit` should show `(healthy)`.

### Step 3: Start the backend

Open a **new terminal** (the backend keeps running in it):

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

The `local` profile reads `.env` by itself, creates or updates the database tables, and allows the web application at `http://localhost:5173`. The first start downloads dependencies and generates code, which takes a few minutes. It is ready when the log shows:

```
Started ErpApplication in … seconds
```

Check it from another terminal: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/auth/csrf` prints `200`.

### Step 4: Create the system administrator (once)

In another terminal:

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local,bootstrap-admin'
```

This one-shot command creates the administrator from the `ERP_BOOTSTRAP_ADMIN_*` values in `.env`, then exits. Running it again does nothing if an administrator already exists.

### Step 5: Start the web application

Open a **new terminal** (it keeps running too):

```bash
cd frontend
npm ci          # first time only: installs the dependencies
npm run dev
```

It is ready when it prints `Local: http://localhost:5173/`.

### Step 6: Load the demo company

In another terminal:

```bash
cd frontend
node --experimental-strip-types e2e/support/seed.ts
```

The seed works only through the public API, like a user would. It creates:

- the company **Demo Trading** (code `DEMO`, US dollars), with its chart of accounts, journals and current fiscal year
- the users **alice**, **bob** and **erin**, each with their roles
- a branch, the warehouse **WH1**, the products **WIDGET** and **GADGET** with opening stock, and reason codes
- the supplier **ACME (Acme Supplies)**, the customer **CUST1 (Contoso Retail)**, payment terms, 10 % purchase and sales taxes and a price list (WIDGET at $25)
- the bank account **Operating USD**
- three employees, a leave type and the payroll set-up (salary structure, monthly schedule)

It finishes with `Seeded company …; users: alice@erp.local, bob@erp.local, erin@erp.local`. Running it again is safe: it reuses what already exists.

The sign-in details, including the two-step secrets, are saved in `frontend/e2e/.state/seed.json`. That file is not committed.

### The demo users

| User | E-mail | Password | Role in the demo |
|---|---|---|---|
| **Alice** | `alice@erp.local` | `Blue-Ocean-Lantern-2026` | Does the daily work: buying, warehouse, selling, invoicing, accounting entries, HR, payroll preparation, company administration |
| **Bob** | `bob@erp.local` | `Blue-Ocean-Lantern-2026` | Approves what Alice prepares: purchase orders, sales overrides, payroll runs; finance and audit |
| **Erin** | `erin@erp.local` | `Blue-Ocean-Lantern-2026` | An employee: self-service only (her leave, attendance, payslips) |
| **Admin** | `admin@erp.local` | `Correct-Horse-Battery-77` | System administrator: users, roles and companies. No access to company data |

Alice and Bob are deliberately different people: the system enforces **segregation of duties**, so the person who prepares a purchase order or a payroll run cannot approve it.

### Step 7: Sign in

Open <http://localhost:5173>.

> **Language.** The ERP opens in **Bangla** (বাংলা). This manual and its screenshots use English: choose **English** in the **বাংলা | English** switch at the top right of the sign-in page. The choice is remembered in the browser and, after you sign in, in your profile, so it follows you to other devices. You can switch at any time in the page header, the user menu or **My account**. See [LOCALIZATION.md](LOCALIZATION.md).

**1. Enter the e-mail address and password.**

![The sign-in page](manual/images/01-sign-in.png)

**2. Enter the 6-digit verification code.** Alice, Bob and Admin have two-step verification turned on. Get the current code in a terminal:

```bash
cd frontend
npm run demo:code alice      # or: bob, admin
```

It prints, for example, `alice@erp.local: 223746  (valid for 23 more seconds)`. Type the code and choose **Verify**.

![Two-step verification](manual/images/02-two-step-verification.png)

- A code is valid for the rest of its 30-second window and works only once. If it is refused, run the command again and use the new code.
- Erin has no two-step verification: she signs in with the password only.
- To use your phone instead, open `frontend/e2e/.state/seed.json`, copy the user's `totpSecret`, and add it in your authenticator app with "Enter a setup key" (time-based). Use this only for the local demo.

**3. You arrive at the dashboard** of your company.

![The dashboard](manual/images/03-dashboard.png)

What you see:

- **Left:** the main navigation. It only shows the areas your roles allow, so Erin sees much less than Alice.
- **Top left:** the company switcher (one user can work for several companies).
- **Top right:** "Search pages" and your account menu (profile, password, two-step verification, sign out).
- **Centre:** key figures for your role. "Open report" opens the detailed report behind each figure.

### Step 8: Find your way

Press **Ctrl K** (or click **Search pages**) and type a few letters of any page name to jump straight to it:

![Search pages](manual/images/04-search-pages.png)

Common screen elements:

- **Lists** have search, filters, sortable column headers and paging at the bottom. Click a number or name to open the record.
- **Documents** (orders, receipts, invoices…) show their status as a coloured badge next to the title. The buttons at the top right are the actions allowed in that status and for your roles; less frequent actions are under **More**. **History** shows every change with who made it and when.
- The server checks every action again. If an action is refused (for example, not enough stock), the message explains why and points to the field concerned.

---

## A.1 Master data at a glance

The demo already contains the basic records. Open them from the navigation:

**Organization → Partners**: customers and suppliers in one list.

![Partners](manual/images/05-partners.png)

**Inventory → Products**: products and their variants (SKUs).

![Products](manual/images/06-products.png)

**Inventory → Stock levels**: what is on hand, reserved for confirmed sales orders, and available, per warehouse ("By location" shows the shelves).

![Stock levels](manual/images/07-stock-levels.png)

---

## A.2 Buying: purchase order → goods receipt → supplier bill

This process buys 10 widgets from Acme, receives them into the warehouse and records Acme's invoice. Sign in as **Alice**.

**1. Create the purchase order.** Go to **Procurement → Purchase orders → New purchase order**. Choose the supplier **ACME** and the warehouse **WH1**. In the first line, choose the item **WIDGET**, enter quantity **10** and unit price **12**. The unit and the tax code are filled in from the product and the supplier. Choose **Save draft**.

![New purchase order](manual/images/10-purchase-order-new.png)

**2. Check and submit it.** The draft shows the totals calculated by the server: 10 × 12 = $120 plus 10 % tax = $132. Choose **Submit** to send it for approval.

![Purchase order draft](manual/images/11-purchase-order-draft.png)

**3. Bob approves it.** Alice cannot approve her own order (segregation of duties), so the **Approve** button is not offered to her. Sign in as **Bob** (for example in a private browser window), open the order and choose **Approve**. The dialog reminds him that orders above the approval threshold need a higher permission.

![Approving the purchase order](manual/images/12-purchase-order-approve.png)

**4. The order is approved.** Back as Alice, the order shows **Approved**, and the next step is available: **Receive goods**.

![Approved purchase order](manual/images/13-purchase-order-approved.png)

**5. Receive the goods.** **Receive goods** creates a draft goods receipt with everything that is still open on the order. You can change quantities or the storage location here if the delivery is partial.

![Goods receipt draft](manual/images/14-goods-receipt-draft.png)

**6. Post the receipt.** Choose **Post** and confirm. Posting puts the 10 widgets into stock at the order's price, and books the stock value in the accounts (inventory against "goods received, not invoiced"). The receipt gets its number (GRN-…), and a posted receipt can no longer be edited; mistakes are corrected with a purchase return.

![Posted goods receipt](manual/images/15-goods-receipt-posted.png)

**7. Record the supplier's bill.** From the receipt choose **Create bill**, enter the supplier's invoice number and the bill date, and choose **Save**. The bill is filled in from the receipt.

![Creating the bill from the receipt](manual/images/16-supplier-bill-from-receipt.png)

**8. Check the 3-way match.** **Check 3-way match** compares the bill with the order (price) and the receipt (quantity). Differences beyond the tolerance block posting until they are corrected, or until someone with the override permission accepts them with a reason.

![3-way match](manual/images/17-three-way-match.png)

**9. Post the bill.** Choose **Post**. The bill is booked as a payable to Acme, and its **open amount** shows what is still to be paid.

![Posted supplier bill](manual/images/18-supplier-bill-posted.png)

---

## A.3 Selling: sales order → delivery → invoice

This process sells 2 widgets to Contoso Retail. Sign in as **Alice**.

**1. Create the sales order.** Go to **Sales → Sales orders → New sales order**. Choose the customer **CUST1** and the warehouse **WH1**, add **WIDGET** with quantity **2**, and **don't enter a price**: prices come from the price list. **Calculate prices** shows what the server will charge (2 × $25 + 10 % tax = $55) before you save.

![Price preview](manual/images/20-sales-order-price-preview.png)

**2. Save the draft.** Choose **Save draft**.

![Sales order draft](manual/images/21-sales-order-draft.png)

**3. Confirm the order.** **Confirm order** shows the customer's **credit check** (credit limit, open invoices and orders, this order) before confirming. Confirming also **reserves the stock**, so it can't be sold twice. A failed credit check can only be overridden by someone with that permission, and they must give a reason.

![Credit check](manual/images/22-sales-order-credit-check.png)

![Confirmed sales order](manual/images/23-sales-order-confirmed.png)

**4. Deliver.** **Deliver** creates a draft delivery for what is still to deliver.

![Delivery draft](manual/images/24-delivery-draft.png)

**5. Post the delivery.** Choose **Post**: the widgets leave the stock (using the reservation), and the cost of goods sold is booked. Use **View** next to "Sales order" to go back to the order, which is now **Delivered**.

![Posted delivery](manual/images/25-delivery-posted.png)

**6. Invoice.** From the order choose **Invoice**. The invoice is filled in from what was delivered.

![Invoice draft](manual/images/26-invoice-draft.png)

**7. Post the invoice.** Choose **Post**. It gets its number (INV-…), the revenue and tax are booked, and the customer owes the **open amount** of $55.

![Posted invoice](manual/images/27-invoice-posted.png)

---

## A.4 Receiving the customer's payment

Still as **Alice**:

**1. Record the payment.** Go to **Accounting → Payments → New payment**. Choose the partner **CUST1**, the bank account **Operating USD**, and the amount **55**. Choose **Save**, then **Post** on the payment page. Posting books the money in the bank account.

![New payment](manual/images/30-payment-new.png)

**2. Allocate it to the invoice.** **Allocate** lists the customer's open items. Enter **55** next to the invoice: "Allocated now" and "Remaining" update as you type. (**Apply** fills the amounts automatically, oldest first.) Choose **Allocate**.

![Allocating the payment](manual/images/31-payment-allocate.png)

![Payment allocated](manual/images/32-payment-allocated.png)

**3. The invoice is settled.** Open the invoice again: its open amount is now **Settled**.

![Settled invoice](manual/images/33-invoice-settled.png)

**4. See the bookings.** **Accounting → Journal entries** lists every entry the processes above created automatically: the receipt, the bill, the delivery, the invoice and the payment. Each entry links back to its document, and posted entries are never changed (corrections are reversals).

![Journal entries](manual/images/34-journal-entries.png)

---

## A.5 Leave: employee request → approval

**1. Erin requests leave.** Sign in as **Erin** and go to **Self-service → My leave**. The top shows her balances. Choose **New leave request**, pick **Annual leave**, the start and end dates and a reason, and choose **Save**. Days are counted in working days, without weekends and public holidays.

![New leave request](manual/images/40-leave-request-new.png)

**2. She submits it.** The new request is a **Draft**. **Submit** sends it for approval, and the days appear as "Pending" in her balance.

![Submitted leave request](manual/images/41-leave-request-submitted.png)

**3. HR approves it.** Sign in as **Alice** (HR manager), go to **HR → Leave requests**, and choose **Approve** on Erin's request. You can add a note. Nobody can approve their own leave.

![Approving the leave request](manual/images/42-leave-request-approve.png)

**4. Erin sees the approval** and the reduced balance.

![Approved leave request](manual/images/43-leave-request-approved.png)

---

## A.6 Payroll: run → calculate → approve → post → pay

**1. Alice creates the run.** Go to **Payroll → Payroll runs → New payroll run**, choose the next open **period**, and choose **Save**.

![New payroll run](manual/images/50-payroll-run-new.png)

**2. Calculate.** Choose **Calculate**. The calculation runs in the background (it takes up to about 15 seconds locally) and the page refreshes by itself. The result shows each employee's gross pay, deductions and net pay. Calculation issues, such as an employee without a salary, are listed so they can be fixed and recalculated. Alice prepared the run, so she is not offered **Approve**.

![Calculated payroll run](manual/images/51-payroll-run-calculated.png)

**3. Bob approves, posts and pays.** Sign in as **Bob** and open the run. Choose:

1. **Approve**.
2. **Post to the ledger**: the salaries, deductions and employer contributions are booked.
3. **Mark as paid**: choose the bank account, and the net pay is booked out of the bank.

![Marking the run as paid](manual/images/52-payroll-run-mark-paid.png)

![Paid payroll run](manual/images/53-payroll-run-paid.png)

**Bank file** downloads the payment file for the bank. It contains every employee's account number, so the system asks Bob to confirm his password first, and the download is recorded in the audit log.

**4. Employees see their payslips.** As **Erin**, **Self-service → My payslips** lists her payslips of posted runs; each opens with its details and a PDF.

![My payslips](manual/images/54-my-payslips.png)

---

## A.7 Reports and dashboards

Sign in as **Bob**.

**Reports → Report centre** lists the reports your permissions allow, grouped by area.

![Report centre](manual/images/60-report-centre.png)

Open a report, for example **Sales by customer**, fill in the parameters and choose **Run report**. Column headers sort the results.

- **Export** creates a CSV, Excel or PDF file in the background; download it from **Reports → Exports**.
- **Save as…** stores the parameters under a name in **Reports → Saved reports**.

![Sales by customer](manual/images/61-report-sales-by-customer.png)

The accounting statements are reports too: trial balance, general ledger, profit and loss, balance sheet, ageing and cash book.

![Trial balance](manual/images/62-report-trial-balance.png)

**Reports → Dashboards** shows key figures per area: executive, sales, purchasing, inventory, finance and HR.

![Dashboards](manual/images/63-dashboards.png)

---

## A.8 Administration

**System administration** (sign in as **Admin**). The administrator manages people and companies, not business data:

- **System users**: invite users, disable them, unlock them, reset their two-step verification.
- **Roles**: the built-in roles and your own custom roles, each a set of permissions.
- **Companies**: create companies.

![System users](manual/images/70-system-users.png)

![Roles](manual/images/71-roles.png)

![Companies](manual/images/72-companies.png)

**Company administration** (sign in as **Alice**, who is the demo company's administrator):

- **Administration → Users and roles**: give roles to the company's users, optionally only for some branches and for a period. An administrator can only hand out permissions they hold themselves, within their own branches, and cannot change their own roles.
- **Administration → Audit log**: who changed what, and when.

![Company users and roles](manual/images/73-company-users.png)

![Audit log](manual/images/74-audit-log.png)

---

# Part B: set up your own company

Use this part to start from an empty system with your own company and people, instead of (or next to) the demo. Steps 1 to 5 of Part A are the same; **skip step 6** (the demo seed).

### B.1 Configure for real use

In `.env`, in addition to Part A step 1:

- Use your own administrator e-mail and a strong password in `ERP_BOOTSTRAP_ADMIN_*`. You can remove them from the file after step B.2.
- Keep `ERP_FIELD_ENCRYPTION_KEYS` safe and unchanged. If it is lost, everyone must enroll two-step verification again.
- To send real e-mails instead of catching them in Mailpit, set `ERP_MAIL_HOST`, `ERP_MAIL_PORT`, `ERP_MAIL_USERNAME`, `ERP_MAIL_PASSWORD` and `ERP_MAIL_FROM`.
- If people will reach the system under another address than `http://localhost:5173`, set `ERP_AUTH_PUBLIC_BASE_URL` (used in invitation links) and `ERP_SECURITY_ALLOWED_ORIGINS` to that address.

All settings are described in the [README's configuration table](../README.md#configuration).

### B.2 First sign-in of the administrator

1. Create the administrator with Part A step 4.
2. Sign in at <http://localhost:5173>. Because administrators must use two-step verification, the system shows **Set up two-step verification** with a QR code.
3. Scan the QR code with your authenticator app (or enter the **Secret key** shown below it), type the 6-digit code from the app, and choose **Confirm and continue**.
4. **Save the recovery codes** that are shown. Each one can replace a code once if you lose your phone.

### B.3 Create your company

As the administrator, open **Companies → New company** and fill in:

- **Code**: a short identifier.
- **Legal and display name**.
- **Country** and **Base currency**: the currency of your accounts. It cannot be changed once there are postings.
- **Time zone**: it defines the company's "today" for document dates.
- **Fiscal year starts in month**.
- Tax and address details.

Creating the company also creates its **standard chart of accounts**, the account mappings used by automatic postings, the journals and the **current fiscal year** with monthly periods.

### B.4 Invite your users and give them roles

1. **System users → Invite user**: enter the person's e-mail and name. They receive an invitation e-mail. Locally the e-mail is in Mailpit at <http://localhost:8025>.
2. The person opens the link, chooses a password and signs in. If their roles require two-step verification, they set it up as in B.2.
3. Give them roles in your company. Either the administrator does it (open the user in **System users**, add a role assignment for the company), or a company administrator does it in **Administration → Users and roles → Assign role**.
4. Choose roles by job. Typical combinations:

| Person | Roles |
|---|---|
| Company administrator | Company administrator |
| Buyer / procurement manager | Buyer; Procurement manager (approves orders) |
| Warehouse staff / manager | Warehouse clerk; Inventory manager |
| Sales staff / manager | Sales representative, Billing clerk; Sales manager (approves overrides) |
| Accountant / controller | Accountant, Accounts receivable clerk, Accounts payable clerk; Financial controller (closes periods) |
| HR | HR manager or HR officer |
| Payroll | Payroll officer (prepares); Payroll approver (approves; must be another person) |
| Every employee | Employee (self-service) |
| Auditor | Auditor (read-only) |

A role can be limited to some branches and to a validity period. **Roles** (system administration) shows exactly which permissions each role contains, and you can create custom roles.

### B.5 Set up your master data

Do this as the company administrator (or the relevant role), in this order, because later records refer to earlier ones:

1. **Organization:** company details (**Organization → Company**), **branches**, **departments**, **tax codes** (purchase and sales), **payment terms**, **exchange rates** (if you use foreign currencies) and document **numbering** if you want other prefixes.
2. **Accounting:** review the **chart of accounts** and **account mappings** (which account each automatic posting uses), add your **bank accounts**, and check the **periods** of the fiscal year.
3. **Partners:** **partner groups**, then **customers** (credit limit, payment terms, tax) and **suppliers**.
4. **Inventory:** **categories**, **attributes** (for variants such as size or colour), **products**, **warehouses** with their locations, and **reason codes** for adjustments. Record your **opening stock** with a stock movement of type opening.
5. **Sales:** **price lists** (one default list per currency, with quantity tiers if needed) and the **sales settings** (discount approval threshold, credit check behaviour).
6. **Procurement:** the **procurement settings** (approval threshold, 3-way match tolerances).
7. **HR:** **positions**, **leave types** and **public holidays**, then the **employees** with their assignments (branch, department, position, manager). Link each employee to their user account so they get self-service.
8. **Payroll:** **pay components**, **salary structures**, a **pay schedule** with its periods, then each employee's **compensation**.

After that, the daily processes work exactly as shown in Part A.

---

# Part C: other ways to run and check it

### C.1 Production-like run with the Docker images

This runs the backend and the web application from their container images, the way they are deployed: database migrations run as a separate step, the API uses the production profile, and the web application is served by nginx with its security headers.

```bash
(cd backend && ./gradlew bootJar)
docker compose --env-file .env -f infra/compose/docker-compose.yml --profile app up --build
```

In another terminal, create the administrator once:

```bash
docker compose --env-file .env -f infra/compose/docker-compose.yml --profile app run --rm bootstrap-admin
```

Open <http://localhost:8088>.

Before starting, set these in `.env`:

- `ERP_API_CURSOR_SIGNING_KEY` and `ERP_FIELD_ENCRYPTION_KEYS` (required in this mode).
- `ERP_SECURITY_ALLOWED_ORIGINS=http://localhost:8088` and `ERP_AUTH_PUBLIC_BASE_URL=http://localhost:8088`. The browser's address must be an allowed origin, or every change is refused with a CSRF error.

To load the demo company into this set-up, run the seed of Part A step 6 with `ERP_APP_ORIGIN=http://localhost:8088` in front of the `node` command.

Stop the Part A backend and dev server first: this mode uses the same ports for the API (8080, 8081).

### C.2 Run the automated checks

```bash
# Backend: formatting, compile, ~1,500 unit and integration tests, coverage, jar (needs Docker)
cd backend && ./gradlew build

# Web application: types, lint, unit tests, production build
cd frontend && npm run typecheck && npm run lint && npm test && npm run build

# End-to-end: the business processes in a real browser (needs Part A running, seeds the demo itself)
cd frontend && npx playwright install chromium   # first time only
cd frontend && npx playwright test
```

### C.3 Refresh the screenshots of this manual

With Part A running:

```bash
cd frontend
npm run docs:screenshots
```

It walks through the processes above in a real browser and replaces the images in `docs/manual/images/`. Each run adds new demo documents.

### C.4 Stop, restart and reset

- **Stop** the backend and the web application with **Ctrl C** in their terminals.
- **Stop** the containers with `docker compose --env-file .env -f infra/compose/docker-compose.yml stop`. Start them again later with step 2. Your data is kept.
- **Reset everything.** This deletes all data, including the demo company, users and files:

  ```bash
  docker compose --env-file .env -f infra/compose/docker-compose.yml down -v
  rm -rf frontend/e2e/.state
  ```

  Then run Part A from step 2.

---

# Troubleshooting

#### Port 5432 is already in use
Another PostgreSQL is running. In `.env`, set `ERP_DB_PORT=5433` and change the port in `ERP_DB_URL` to `5433` (`jdbc:postgresql://localhost:5433/erp`), then start step 2 again.

#### "Invalid verification code"
The code changes every 30 seconds and each code works only once. Run `npm run demo:code <user>` again and use the new code immediately. Also check that your computer's clock is correct.

#### Two-step verification stopped working after a restart
`ERP_FIELD_ENCRYPTION_KEYS` was empty, so a temporary key was used. Set the key (Part A step 1). Then either reset the database (C.4), or have the administrator reset the user's two-step verification so they enroll again. For the demo users, also delete `frontend/e2e/.state` and run the seed again.

#### "Account locked" or "Too many attempts"
After 5 wrong passwords an account is locked for 15 minutes, and sign-ins are limited per minute. Wait, or have an administrator unlock the user in **System users**.

#### The web application shows errors or a blank page
- Make sure the backend is running and printed `Started ErpApplication`.
- If the page was open while you updated the code, reload it with **Ctrl Shift R**.
- If problems persist after dependencies changed, stop `npm run dev`, delete `frontend/node_modules/.vite`, and start it again.

#### "Origin not allowed" / "CSRF" errors
The address in the browser must be listed in `ERP_SECURITY_ALLOWED_ORIGINS`. Locally, `http://localhost:5173` is allowed by default. Use `localhost`, not `127.0.0.1`.

#### The payroll calculation never finishes
The calculation runs as a background job. Make sure `ERP_JOBS_ENABLED` is not set to `false` (the local profile turns it on), and look at the backend log for errors.

#### Reports fail with a database permission error
A database created before reporting existed has no password for the reporting role. Run this once as the database superuser, using the value of `ERP_DB_REPORTING_PASSWORD`:

```sql
ALTER ROLE erp_reporting PASSWORD '<value of ERP_DB_REPORTING_PASSWORD>';
```

Alternatively, reset the database (C.4).

#### No e-mail arrives
Locally every e-mail goes to Mailpit: open <http://localhost:8025>.
