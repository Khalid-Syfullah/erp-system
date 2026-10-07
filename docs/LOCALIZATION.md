# Localization

The web application is bilingual. **Bangla (bn-BD) is the primary and default language**. English (en) is the secondary language and the fallback for any message that has no Bangla translation. Only the presentation is localized. The API, the database, identifiers, enum values and every calculation stay the same in both languages (ADR-043).

## 1. Configuration

| Setting | Value | Where |
|---|---|---|
| Default language | Bangla, `bn` (locale `bn-BD`) | `DEFAULT_LANGUAGE`, `src/i18n/index.ts` |
| Secondary language | English, `en` (locale `en`) | `LANGUAGES`, `src/i18n/index.ts` |
| Fallback language | English | `FALLBACK_LANGUAGE`, `src/i18n/index.ts` |
| Default currency display | BDT as `৳`, symbol first: `৳২৫,০০০.০০` | `formatMoney`, `src/lib/format.ts` |
| Number system | Bengali digits with the lakh/crore grouping in Bangla (`১,২৩,৪৫৬`); ASCII digits with thousands grouping in English | `Intl.NumberFormat('bn-BD' \| 'en')` |
| Font | Noto Sans Bengali (variable, SIL OFL), self-hosted behind Geist | `src/index.css` |
| Document language | `<html lang="bn">` in `index.html`; set to `bn-BD` or `en` at start-up | `activateLanguage` |

The currency of a document is the document's currency: a USD invoice shows `US$৫৫.০০` in Bangla and `$55.00` in English. BDT is a display default only. It doesn't change a company's base currency or the currency of any document.

## 2. Architecture

```
src/i18n/
  en.ts         English catalog: the source of keys (Messages = typeof en, MessageKey = every leaf path)
  bn.ts         Bangla catalog: same shape (type Catalog), plus errors, fieldErrors, enums and serverText
  index.ts      language state, lookup (t, tryT, tryOwn), enumLabel, serverText, searchable
  i18n.test.ts  coverage and formatting tests (§8)
src/auth/language.tsx   the preference: LanguageSwitcher, changeLanguage, syncProfileLanguage
src/lib/format.ts       numbers, money, dates, quantities and decimal input in the active locale
```

- **Components never contain display text.** They call `t('section.key', vars)`. The compiler checks every key against `en.ts`. `bn.ts` has the same type with all English keys present, and the tests also check this.
- **The language is fixed when the application loads.** Some messages (form validation texts in zod schemas) are read once at module load. A switch therefore saves the choice and reloads the page, so a mix of languages can never be on screen.
- **Lookup.** `t(key)` looks in the active catalog, then in English, then falls back to the key itself. `tryT(key)` is the same for keys computed at runtime (enum values, error codes) and returns `undefined` when nothing matches. `tryOwn(key)` looks only in the active catalog, and is used where the server already sends a usable English text (§5).
- **Message variables.** `{name}` placeholders are filled in. A number is formatted with the active language's digits (`৩টি নির্বাচিত`). A string is inserted as it is, so identifiers keep their characters (`INV-2026-000123 কর্তৃক`).

## 3. What is translated, and what is not

| Translated (display only) | Never translated |
|---|---|
| All UI text: navigation, pages, forms, buttons, tables, dialogs, toasts, empty and loading states, confirmations | API paths, JSON field names, enum values, permission codes, error codes |
| Enum values shown to people (`APPROVED` → `অনুমোদিত`) via `enums` | The enum values themselves, in requests, filters and URLs |
| API errors by code (`errors.<CODE>`) and field errors by code (`fieldErrors.<CODE>`) | Database values and seeded master data |
| Texts the server words in English: report names, descriptions, parameters and columns; dashboard and widget titles; role names; permission descriptions (`serverText`) | Document numbers, SKUs, codes, IDs, account numbers, IBANs, phone numbers, email addresses, URLs |
| Numbers, amounts, quantities, percentages, dates, times and month names | Data the user enters: partner, product, employee and account names, notes, chart-of-accounts names |

Master data (account names, leave type names, product names) is shown as the company entered it. A company that keeps its books in Bangla enters Bangla names.

## 4. Language preference

The order is: **the signed-in user's saved choice → this browser's saved choice → Bangla**.

1. **Profile (every device).** `auth.users.locale` (`PATCH /api/v1/me`, `locale`) holds the user's explicit choice: `bn-BD`, `en` or `en-GB`. `null` means "not chosen". After sign-in and on every start, `syncProfileLanguage` adopts the profile's language. If it differs from the running language, the page reloads into it, and after a sign-in the reload lands on the page the sign-in leads to.
2. **Browser.** `localStorage['erp.language']` (`bn` or `en`) holds the choice before sign-in, and keeps the profile's choice for the next start. Without storage (private mode, blocked), the choice lasts for the page.
3. **Default.** With no choice anywhere, the application is in Bangla. The browser's `Accept-Language` is not consulted. Bangla is the product's default, not a guess.

**Switching.** `বাংলা | English` is shown on the sign-in pages, in the header (from the `sm` breakpoint up), in the user menu and on the account page.

- Signed in: the choice is saved to the browser and to the profile (`If-Match` on the user's version), then the page reloads.
- Signed out: the choice is saved to the browser and marked pending (`erp.language.pending`). At the next sign-in it is written to the profile, because it is the most recent explicit choice, and the profile's older value doesn't override it.
- If the profile write fails (for example a version conflict), the choice stays pending and is written at the next sign-in.

**Never overridden.** No code path replaces a saved choice with the default. A profile with `locale = null` leaves the browser's choice in place, and a browser with no saved choice follows the profile. The `en` that every account had before this change was a column default, not a choice. Migration `V202610120900__auth__optional_locale.sql` resets it to `null`, so those accounts start in Bangla like new ones.

**Formatting locale.** The profile's locale refines formatting only within the same language (`en-GB` gives `07/10/2026` under English). A profile locale in another language never changes the digits of the running language.

## 5. Server texts and errors

- **API errors.** `problem.code` is looked up in `errors.<CODE>`. Every code in API.md has a Bangla text. The server's `detail` is English and is shown in English only. In Bangla, the code's generic text is shown instead.
- **Field errors.** `fieldErrors.<CODE>` gives the Bangla text (`INSUFFICIENT_STOCK` → `পর্যাপ্ত মজুত নেই`). An unknown code shows the server's message. In English the server's message is always shown, because it is more specific.
- **Server-worded labels.** Reports, dashboards, widgets, roles and permissions come from seeded rows with English labels. `serverText(text)` looks the label up in `bn.serverText`, keyed by the exact English text, and returns it unchanged when there's no entry. A new report, column or permission therefore shows in English until it gets an entry. That is visible but harmless.

## 6. Numbers, amounts, dates and input

| Value | Bangla | English |
|---|---|---|
| Quantity `123456` | `১,২৩,৪৫৬` | `123,456` |
| Amount `25000` BDT | `৳২৫,০০০.০০` | `BDT 25,000.00` |
| Negative `-1234567.5` BDT | `-৳১২,৩৪,৫৬৭.৫০` | `-BDT 1,234,567.50` |
| Date `2026-10-07` (long) | `৭ অক্টোবর, ২০২৬` | `October 7, 2026` (`7 October 2026` with an `en-GB` profile) |
| Identifier `INV-2026-000123` | `INV-2026-000123` | `INV-2026-000123` |

- **Precision.** Amounts and quantities arrive as decimal strings and are formatted with `decimal.js` and `Intl.NumberFormat` from the string. They're never converted to `number`. Only the digits and grouping change. The fraction digits are the same in both languages (the currency's minor unit, or the scale the API returns), and the tests compare every value in both languages as a `Decimal`.
- **Input.** Decimal fields accept Bengali or ASCII digits and either grouping (`১,২৩,৪৫৬.৫০` → `"123456.5"`). The request always carries an ASCII decimal string.
- **Dates and times** use the user's (or company's) IANA time zone, by default `Asia/Dhaka`. Native date pickers are rendered by the browser in its own locale.
- **Identifiers are never re-digited.** Document numbers, codes, SKUs and IDs are inserted as text, not formatted as numbers.

## 7. Bangla text and search

- **Unicode NFC.** The server stores every text field in NFC (except `@RawText` fields such as passwords), and normalizes `q` and text filters the same way. Bangla has letters with two encodings (য় as U+09DF or U+09AF U+09BC), and both are found by a search for either. Client-side filters use `searchable()` (NFC plus lower case).
- **Search across languages.** The report centre and the permission list match both the displayed Bangla label and the English source text, so `রেওয়ামিল` and `trial` both find the trial balance.
- **Rendering.** Noto Sans Bengali is bundled with the application (CSP `font-src 'self'`). Line heights are Tailwind's defaults, which fit Bangla's vowel signs above and below the line.

## 8. Terminology

The catalog uses natural Bangladeshi business Bangla, with established accounting terms where Bangladeshi accountants use them and English loanwords where those are what people say at work (ইনভয়েস, পোস্ট, ডেবিট, ক্রেডিট).

| English | Bangla | Note |
|---|---|---|
| Accounting | হিসাবরক্ষণ | |
| Chart of accounts | হিসাব তালিকা | |
| Journal / Journal entry | জাবেদা বই / জাবেদা দাখিলা | |
| General ledger / Ledger | সাধারণ খতিয়ান / খতিয়ান | |
| Trial balance | রেওয়ামিল | |
| Balance sheet | উদ্বৃত্তপত্র | |
| Income statement | আয় বিবরণী | |
| Cash book | নগদান বই | |
| Opening / Closing balance | প্রারম্ভিক জের / সমাপনী জের | |
| Fiscal year / Period | অর্থবছর / হিসাবকাল | Payroll period: বেতনকাল |
| Debit / Credit | ডেবিট / ক্রেডিট | |
| Receivables / Payables | প্রাপ্য হিসাব / প্রদেয় হিসাব | |
| Reversal / Reverse | বিপরীত দাখিলা / বিপরীত করুন | |
| Payment | পেমেন্ট | |
| Tax / Tax code | কর / কর কোড | |
| Business partners | ব্যবসায়িক পক্ষ | Customers: গ্রাহক, suppliers: সরবরাহকারী |
| Inventory / Stock | মজুত | |
| Warehouse / Location | গুদাম / অবস্থান | |
| Product / Variant / Unit | পণ্য / রূপভেদ / একক | |
| Adjustment / Transfer / Valuation | সমন্বয় / স্থানান্তর / মূল্যায়ন | |
| Procurement / Purchase requisition / Purchase order | ক্রয় / ক্রয় চাহিদাপত্র / ক্রয় আদেশ | |
| Goods receipt / Supplier bill | পণ্য গ্রহণ / সরবরাহকারীর বিল | |
| Sales / Quotation / Sales order | বিক্রয় / কোটেশন / বিক্রয় আদেশ | |
| Delivery / Invoice / Credit note | ডেলিভারি / ইনভয়েস / ক্রেডিট নোট | |
| Price list / Payment terms / Credit limit | মূল্য তালিকা / পরিশোধের শর্ত / ক্রেডিট সীমা | |
| Human resources / Employee | মানবসম্পদ / কর্মচারী | |
| Branch / Department | শাখা / বিভাগ | |
| Leave requests | ছুটির আবেদন | |
| Payroll / Payroll run / Payslip | বেতন ব্যবস্থাপনা / বেতন প্রক্রিয়া / বেতন স্লিপ | |
| Gross / Net / Deduction / Employer contribution | মোট বেতন / নিট বেতন / কর্তন / নিয়োগকর্তার অবদান | |
| Report / Dashboard / Export | প্রতিবেদন / ড্যাশবোর্ড / রপ্তানি | |
| Administration / Audit log / Roles | প্রশাসন / নিরীক্ষা লগ / ভূমিকা | |
| Draft / Posted / Approve / Reject / Submit / Post | খসড়া / পোস্টকৃত / অনুমোদন করুন / প্রত্যাখ্যান করুন / জমা দিন / পোস্ট করুন | |
| Self-service / Two-step verification | স্ব-সেবা / দ্বি-ধাপ যাচাই | |

Buttons use the polite imperative (`সংরক্ষণ করুন`, `পোস্ট করুন`). States use the past participle (`পোস্টকৃত`, `অনুমোদিত`). A new text uses the glossary's term. A new domain term goes into this table first.

## 9. Adding or changing a text

1. **Add the English text** to `src/i18n/en.ts` under the screen's section, and use it with `t('section.key')`. Never write a display string in a component.
2. **Add the Bangla text** under the same key in `src/i18n/bn.ts`. Keep every `{placeholder}`. Use the glossary.
3. **A new enum value** shown to people gets `enums.VALUE` in both catalogs, or `accountSubtypes` for account subtypes.
4. **A new error code** gets `errors.CODE` in both catalogs. A new field-error code gets `fieldErrors.CODE` in `bn.ts`; English shows the server's message.
5. **A new report, column, parameter, dashboard, role or permission** seeded with an English label gets a `serverText` entry in `bn.ts`, keyed by the exact English text.
6. **Run `npm test`.** `src/i18n/i18n.test.ts` fails when:
   - a Bangla key is missing;
   - a placeholder differs;
   - an enum value offered by a screen has no Bangla label;
   - a Bangla message contains an English word outside the allowlist of codes and product names.
7. **A new page** reachable from the navigation is checked in Bangla by `e2e/localization.spec.ts` (it opens every navigation page in Bangla and fails on API errors, console errors or accessibility violations).

A third language needs:
- a catalog shaped like `bn.ts`;
- an entry in `LANGUAGES`;
- the `Language` type extended;
- a switcher label;
- a font, if its script needs one.

## 10. Tests

| Test | Covers |
|---|---|
| `src/i18n/i18n.test.ts` | Full Bangla coverage, placeholders, enum labels, no stray English. Default Bangla. Persistence in both directions. English fallback. Bangla formatting (digits, lakh grouping, ৳, dates). Bengali digit input. Identical values in both languages. Server texts. Field errors. NFC search. |
| `src/auth/language.test.tsx` | The switcher. A choice saved to the profile (`PATCH /me`, `If-Match`). A profile choice adopted after sign-in, landing on the destination page. A choice made before sign-in written to the profile. |
| `src/lib/format.test.ts` | Formatting within a language. A foreign profile locale does not change it. |
| `e2e/localization.spec.ts` | First visit in Bangla. Switching and persistence across reloads, sign-in and devices. Bangla tables, statuses, validation and Bangla-name search. The same invoice, stock and payroll figures in both languages. Every navigation page in Bangla. |
| `UserAdministrationIntegrationTest` | A profile `locale` is `null` until chosen, can be set and cleared. |
| `JsonConfigurationTest`, `PartnersIntegrationTest` | NFC storage (`@RawText` untouched). A Bangla name found whichever encoding is typed. |

The other end-to-end specs pin English (`erp.language = en` in their storage state) so their English selectors keep working. Unit tests run in English unless a test switches language.
