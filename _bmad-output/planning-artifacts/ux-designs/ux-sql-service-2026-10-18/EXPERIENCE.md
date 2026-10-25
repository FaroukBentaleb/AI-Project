---
name: sql-service SQL module — Experience
status: final
created: 2026-10-18
updated: 2026-10-18
sources:
  - ../../prds/prd-sql-service-2026-10-18/prd.md
  - ../../prds/prd-sql-service-2026-10-18/addendum.md
  - ../../epics-sql-service.md
  - DESIGN.md
---

# sql-service SQL module — Experience Spine

## Foundation

- **Form factor:** desktop web only, designed at ~1280 px. Below 900 px the layouts stack as a fallback. No mobile surface is designed.
- **Stack:** Angular 18 standalone feature module, loaded inside the DevAssist Hub shell's bare `router-outlet`. The module has **no UI library**: components are hand-rolled, and this spine specifies their full behavior.
- **Visual identity:** `DESIGN.md` (Midnight Terminal, dark only). Tokens are referenced as `{path.to.token}`, and component names are identical in both spines.
- **Scope:** the SQL module's screens only. The app-wide shell is owned by the DevAssist Hub team, so each SQL screen renders its own module header.
- **Backend contract:** every call goes through the gateway under `/api/sql/**` (PRD §5, addendum §B). Every request carries `X-User-Id` from the stored display name (FR-33). The `/api/sql/**` route timeout is 15 s (22 s under Plan B, the PRD §6 fallback with an 8 s LLM timeout); generation is the only call that comes close to it.
- **Product rules that shape the UI:**
  - The module never executes SQL.
  - It never auto-saves Generated SQL.
  - There is **no schema-hint field** anywhere. Schema knowledge comes only from Examples (FR-13).
- **Module session state:** a module-scoped state service holds the last Generate prompt and result, and the last Validate input and result. They survive in-app navigation (Example links, Save → Cancel, tab switches) and are cleared by a new run or a full page reload. The Save prefill is handed over through this service, not through the URL.
- **Precedence over the epics:** where the epics' frontend stories differ from this spine, the spine wins. The known deltas:
  - Library SQL previews clip at 6 lines with *Show all*, where Story 3.2 says "scroll if long".
  - Examples show the title only, because `examples_used` carries only `id` and `title`.

→ Visual reference for every surface below: [`mockups/wireframes-all-screens.html`](mockups/wireframes-all-screens.html). If the mockup and this spine disagree, the spine wins. The mockup's annotations predate this spine. `.working/` holds non-normative exploration (themes, layouts).

## Information Architecture

| # | Surface | Route | Reached from | Purpose | FRs |
|---|---|---|---|---|---|
| 01 | Library | `/sql/library` (default) | *Library* tab; module entry; after a delete | Browse, search, filter, and copy Saved Queries, with the SQL inline | FR-27, FR-28 |
| 02 | Query detail | `/sql/queries/:id` | Card title; *Show all*; Example link; after a save; deprecation banner link | Every attribute, trust signals, copy, edit, deprecate or undeprecate, delete | FR-31, FR-28 |
| 03 | Save form | `/sql/queries/new` (`?from=generate` / `?from=validate` / `?from=manual`) | Header *+ New query*; *Save to library* on Generate or Validate; *Save a query manually* on a generation outage | Create a Saved Query; only Title and Description are required | FR-1, FR-29 |
| 04 | Generate | `/sql/generate` | *Generate* tab; *Generate "<term>"* from an empty Library search | NL Prompt → Generated SQL, Examples, Warnings, Safety Note | FR-29, FR-30, FR-13–FR-19 |
| 05 | Validate | `/sql/validate` | *Validate* tab | Paste SQL → Validation Result and Safety Note | FR-32, FR-12 |
| 06 | Edit | `/sql/queries/:id/edit` | *Edit* on Query detail | Change a Saved Query, with optimistic locking | FR-5, FR-31 |
| 00 | Identity modal | Over any SQL route | First load with no stored display name; user menu → *Change display name* | Capture the display name used as `X-User-Id` | FR-33 |

**Navigation model.**
- The module header carries three tabs: Library · Generate · Validate. Detail, Save, and Edit keep *Library* active, because they live under it.
- Header *+ New query* is always available.
- Detail and Edit show a breadcrumb.
- Modals go at most one level deep.

**URL state.**
- Library search, filters, sort, and page are all in the query string, so a link reproduces the view and Back restores it.
- Generate and Validate results live in module session state (see Foundation), not in the URL.

**Closure.** Every PRD frontend need lands on a surface (table above), and every surface is reached by a Key Flow. FR-34 and FR-35 (Could) have no v1 surface.

## Voice and Tone

Microcopy rules. The brand voice is in `DESIGN.md` § Brand & Style.

- **Plain, specific, and short.** Name the thing, the cause, and the next step.
- **The UI never overclaims safety.** Use "read-only check", "structural check", and "verified … not verified", never "safe" or "secure".
- **The Safety Note is rendered verbatim** from the response's `safety_note` (both generate and validate return it). The UI holds no copy of it.
- **Error-copy rule (the contract):**
  1. Use the client message map below, keyed by the envelope's `error` code.
  2. Otherwise, use the server `message`.
  3. Otherwise, use the generic message.
  
  Each string is tagged **[PRD]** (exact text from the PRD) or **[UI]** (written here).

| Code / condition | Message | Source |
|---|---|---|
| `LLM_UNAVAILABLE`, gateway timeout | "SQL generation temporarily unavailable. You can save a query manually." | [PRD] |
| `LLM_QUOTA_EXCEEDED` | "The team's AI quota is used up for now. You can save a query manually." | [UI] |
| `LLM_NOT_CONFIGURED` | "SQL generation isn't configured on this server. You can save a query manually." | [UI] |
| `LLM_REFUSED` | "The model declined to generate SQL for this request." + " Try rephrasing." | [PRD] + [UI] |
| `SQL_PARSE_ERROR` (generate) | "The model's reply couldn't be read as SQL. Try rephrasing." | [UI] |
| `SQL_REJECTED` | "The generated SQL was rejected by the read-only check. It is shown for reference only and cannot be copied or saved from here." | [UI] |
| `CONFLICT` | "Someone else saved changes to this query. We loaded their version and kept your changes — review and save again." | [UI] |
| `NOT_FOUND` | "This query doesn't exist or was deleted." | [UI] |
| `VALIDATION_FAILED` | The server `message`, shown inline on `field` | server |
| No-similar Warning | "No similar queries found — verify table names in the generated SQL." | [PRD] |
| RAG-unavailable Warning | The server Warning text, verbatim | server |
| Unknown code, non-JSON body, or network failure | "Service unavailable. Try again in a moment." | [UI] |

| Do | Don't |
|---|---|
| "Copied. This query is not read-only — it modifies data." | "Warning!!! Dangerous query" |
| "Checks the statement structure — not whether it's correct or safe on your schema." | "Make sure your SQL is safe" |
| "Saved · added to team examples" / "Saved · not yet indexed (team examples unavailable)" | "Success!" |
| `DATA_MODIFYING_CTE` + "CTE 'x' contains an INSERT" | Either the code alone or the prose alone |
| "team examples" | "RAG", "Qdrant", "embeddings", or "vector" |

**Glossary in the UI.**

| PRD term | UI copy |
|---|---|
| Saved Query | "query" |
| Team Library | "Library" |
| NL Prompt | "prompt" |
| Examples / RAG Index | "team examples" |
| Read-Only Flag | "Read-only ✓" / "Not read-only" |
| Deprecate / restore (FR-7) | *Deprecate* / *Undeprecate* |
| Safety Note | "Safety note." |

**Fixed notices.**
- Generate: "Don't include personal data — prompts and team examples are sent to an external AI model." (FR-29)
- Description helper: "What does it return and when should a teammate use it?"
- Edit: "Changing SQL re-checks read-only status and team-example indexing."
- Validate explainer: "Checks the statement structure — not whether it's correct or safe on your schema."

## Component Patterns

Behavior only. The visual specs are in `DESIGN.md` § Components, under the same names.

| Component | Where | Behavioral rules |
|---|---|---|
| Module header | Every SQL screen | The active tab reflects the surface. *+ New query* opens a blank Save form. |
| User chip and user menu | Header | The chip opens a menu with one item, *Change display name*, which opens the identity modal prefilled. It follows the ARIA menu-button pattern: Enter, Space, or ↓ opens it; Esc closes it; focus returns to the chip. |
| Breadcrumb | Detail, Edit | `Library › <title>` (› `Edit`). *Library* restores the last Library URL, including filters. The title is truncated with an ellipsis, and the full title is in `title=`. |
| Query card | Library | The title link opens detail. *Copy* copies the **full** SQL (not the clipped preview), calls `POST …/usage`, and flips to "Copied ✓" for ~2 s. The use count bumps optimistically. If the usage call fails, the count stays bumped and the failure is logged silently, because usage tracking never blocks the copy. On a not-read-only query, copy also shows the warning banner under the card. *Show all (n lines)* opens detail and appears only when the SQL runs past 6 lines. |
| Skeleton card | Library, detail loading | Three cards in the Library; one main block plus the aside on detail. They are replaced in place when data arrives. They are `aria-hidden`, and a polite "Loading" is announced once. |
| Code block | Card, detail, Generate, Validate | Read-only and selectable. Code blocks have no inline links. A rejected block on Generate has **no** Copy and no Save. |
| SQL textarea | Save, Edit, Validate | Editable monospace that grows with content. Limit 20,000 chars. The counter appears past 19,000. Above 20,000 the field is invalid and the commit button is disabled. Tab inserts two spaces; Esc then Tab leaves the field `[ASSUMPTION]`. |
| Text input (search) | Library | Matches title and description (`q`). Debounced ~300 ms `[ASSUMPTION]`; Enter applies immediately. Resets to page 1. |
| Select (sort) | Library | Newest (default), Most used, Recently used, Title. These map to `created_at desc`, `use_count desc`, `last_used_at desc`, and `title asc`. |
| Filter bar | Library | Filters combine with AND and with `q`. Every change resets to page 1 and writes to the URL. *Clear filters* shows only when something is set. Parts: **tag filter** (tags input; any-of, so it matches queries with any listed tag; maps to `tags=`); **author filter** (text input, exact author name, applied on Enter or blur; maps to `author=`); **LLM-generated only** (filter switch); **Read-only** (segmented control: Any · Yes · No); **Deprecated** (segmented control: **Hide** by default · Show). No option lists are fetched, because the API has no tags or authors endpoint. |
| Filter switch | Filter bar | `role="switch"` with `aria-checked`. Space toggles it. |
| Segmented control | Filter bar | `role="radiogroup"`. Arrow keys move the selection and apply it immediately. |
| Tags input | Save form, tag filter | Enter or comma commits the typed text as a chip. Backspace in an empty input removes the last chip. Each chip's × is a button labelled "Remove tag <name>". Text is trimmed and lowercased `[ASSUMPTION]`, and duplicates are ignored. Save form limits: 0–10 tags, each ≤ 50 chars; past a limit, the input refuses the chip and shows an inline error. |
| Pager | Library | 20 per page. "1–20 of 23" plus page buttons. No infinite scroll. |
| Badges | Card, detail | Status only; not interactive. Detail also shows *Read-only ✓* and the team-example status. |
| Banner | Inline in context | Persistent until the condition clears or the user navigates away. Not dismissible. |
| Command bar | Generate | The prompt textarea starts at one row and grows. The live counter enforces 1–1000 chars: *Generate* is disabled when the prompt is blank or over 1000. Ctrl/⌘+Enter submits. While a request is in flight: the button reads "Generating…" and is disabled, the prompt is read-only, and the generating indicator shows. |
| Generating indicator | Generate | Text: "Finding similar team queries, then generating…". The previous result stays visible but dimmed until it is replaced `[ASSUMPTION]`. |
| Result tabs | Generate (success only) | SQL · Examples (n) · Warnings (n). Counts always show, including (0). The Warnings label turns warning-colored when n > 0. ARIA tabs with automatic activation: arrow keys move and select, Home and End jump. A new result resets to the SQL tab. Tabs are not rendered for a 422 or 503 result. |
| Examples list | Generate, Examples tab | One row per Example: its title, linking to its detail in the same tab. Session state preserves the result, so Back returns to it. With none: "No team examples were used." |
| Safety Note | Generate, Validate | Shows with every SQL result, accepted or rejected, outside the tabs. Never collapsible, never dismissible. The text is the server's `safety_note`. |
| Validation result | Validate | Header: "✕ Rejected · n problem(s)" or "✓ Accepted · single read-only SELECT". Then every Rejection Code (code chip plus the reason), then Warnings (n) when present, then **Normalized SQL** for both outcomes (it is what was actually validated). *Accepted* adds Copy and *Save to library*. A new run replaces the previous result. |
| Save form | Save, Edit | Required: Title (≤ 200), Description (≤ 2000), and SQL (≤ 20,000), with live counters. Validation runs on submit, then live per field; on submit, focus moves to the first invalid field. *More options ▸* holds Tags, Notes, and Dialect (PostgreSQL only); it is collapsed on create, and on edit its label summarizes the values. Prefill: see *Save form prefill by entry point* below. *Cancel* returns to the previous surface, where session state is intact. |
| Discard-changes modal | Save, Edit | Shown when the user leaves a form with unsaved edits (navigation, tab switch, *Cancel*). Modal "Discard your changes?" with *Keep editing* (initial focus) and *Discard*. Browser close or reload uses the native `beforeunload` prompt. It never stacks: it only appears when no other modal is open. |
| Replaced-by picker | Deprecate modal | ARIA combobox with a listbox. Searches after 2 characters (`q` against non-deprecated queries, excluding this one; 8 results). ↑/↓ move, Enter picks, Esc closes the list. States: "Searching…", "No matching queries", and the selected value as a removable chip. Optional. |
| Deprecate modal | Detail | Reason is required (counter /500 `[ASSUMPTION: UI-only limit]`). *Deprecate* is disabled until a reason is entered. Includes the Replaced-by picker. While submitting, the button reads "Deprecating…". On a 400 or 404, the error shows inline in the modal and the modal stays open. |
| Delete modal | Detail | Title "Delete '<title>'?", body "It will be removed from the library and team examples." Focus starts on **Cancel**. While submitting, the button reads "Deleting…". On success: return to the Library and show the toast (see the post-mutation toast rule). On a 404: return to the Library and show the toast "Already deleted". |
| Identity modal | First visit, user menu | "What should we call you?" Display name: trimmed, 1–100 chars. *Continue* is disabled when empty. Stored in `localStorage` and sent as `X-User-Id`. Cannot be dismissed on first visit; cancelable when changing the name. |
| Toast | Global, bottom-right `[ASSUMPTION]` | One at a time. Auto-dismisses after ~5 s and pauses on hover or focus. Announced politely. No actions. |
| Empty state | Library | One line and up to two actions (see State Patterns). |

**Save form prefill by entry point.**
1. **From Generate:** the SQL and the original prompt (shown as a read-only line); is_llm_generated = true.
2. **From Validate:** the normalized SQL; is_llm_generated = false.
3. **Manual (outage):** the NL Prompt goes into Notes, *More options* is expanded, and is_llm_generated = false (Story 7.3).
4. **Header +:** blank.

**Post-mutation toast rule** (Save, Edit, Undeprecate). The choice is keyed on the response, never on `indexed` alone:
1. If `warnings[]` is non-empty → warning toast "Saved · not yet indexed (team examples unavailable)".
2. Otherwise, if `read_only = false` or `deprecated = true` → "Saved".
3. Otherwise → "Saved · added to team examples".

Undeprecate uses the same rule with "Undeprecated" in place of "Saved". Deprecate → "Deprecated · removed from team examples". Delete → "Deleted '<title>'".

## State Patterns

| Surface | State | Treatment |
|---|---|---|
| Library | Loading | Skeleton cards. The filter bar stays interactive. |
| Library | Empty library | Not reachable in practice, because the Seed Corpus is always loaded. If it happens: "No queries yet." with *+ New query* and *Generate*. |
| Library | No results | Empty state: "No queries match '<q>'." with *Clear filters* and *Generate "<q>"*, which opens Generate with the prompt prefilled, **not** submitted. |
| Library | Copied | See Query card. |
| Library | Copied, not read-only | Warning banner under that card: "Copied. This query is not read-only — it modifies data." |
| Library | Deprecated shown | The card shows the *Deprecated* badge and follows the sort; it is not pinned. |
| Library | Load failed | Danger banner with the generic message and *Retry*. |
| Detail | Loading | Skeleton main block and aside. |
| Detail | Default | Breadcrumb, title, badges, actions (Copy SQL · Edit · Deprecate · Delete), full SQL, and the aside: description, author, created · updated, dialect, tags, origin, usage, read-only status, notes, team-example status. |
| Detail | LLM-generated | The Origin row shows the badge and "Original prompt: '…'". |
| Detail | Deprecated | Neutral banner: "Deprecated: replaced by '<link>' — reason: …" (the link appears only when replaced_by is set). The action reads *Undeprecate*, and the **Team examples** aside row reads "Not used as a team example". |
| Detail | Not read-only | Danger banner: "Not read-only. Validation reasons:", then each code chip with its reason. Copy is still allowed and shows the notice. |
| Detail | Eligible but not indexed | The **Team examples** aside row: "Not yet indexed — will be added automatically." |
| Detail | 404 | `NOT_FOUND` message with *Back to Library*. |
| Detail | Undeprecating | The action is disabled and reads "Undeprecating…". Toast per the post-mutation rule. |
| Save | Validation error | The field gets an invalid border and the message underneath; it is announced, and focus moves to the first invalid field. |
| Save | Submitting | *Save* is disabled and reads "Saving…". Indexing is synchronous and can take up to ~3 s (PRD §7). |
| Save | Saved | The user lands on detail. Toast per the post-mutation rule. Indexing never fails a save. |
| Save | 400 from server | Inline on `field`, or as a form-level danger banner when there is no `field`. |
| Generate | Idle | Command bar and the personal-data notice. If session state holds a result, that result. |
| Generate | Generating | See Command bar and Generating indicator. |
| Generate | Success | Result tabs on SQL, Copy and *Save to library* on the SQL tab, and the Safety Note. |
| Generate | No Examples | Examples (0). Warnings holds the no-similar Warning (FR-15). |
| Generate | Team examples unavailable | Warnings holds the server's RAG-unavailable Warning (FR-16). |
| Generate | `SQL_REJECTED` (422) | Danger banner (message map), the rejected SQL (no Copy, no Save), the reasons as code chips, *Rephrase and retry* (focuses the prompt), and the Safety Note. No tabs. |
| Generate | `LLM_REFUSED` / `SQL_PARSE_ERROR` (422) | Warning banner (message map). The raw model reply is not shown in v1 `[ASSUMPTION]`. |
| Generate | `LLM_UNAVAILABLE` (503) or gateway timeout | Warning banner with *Save a query manually* and *Retry in ~Ns*, which counts down from `Retry-After` / `retry_after_seconds` and enables at 0. With no hint, *Retry* is enabled at once. |
| Generate | `LLM_QUOTA_EXCEEDED` (503) | Warning banner with the same two actions. |
| Generate | `LLM_NOT_CONFIGURED` (503) | Danger banner with *Save a query manually* only; no retry. |
| Generate | 400 | Inline under the prompt. Normally prevented by the client limits. |
| Validate | Idle | Explainer line, the paste area, and *Validate* with the Ctrl/⌘+Enter hint. *Validate* is disabled while the paste area is empty or over 20,000 chars. |
| Validate | Validating | The button is disabled. The result appears in place; it normally takes < 300 ms. |
| Validate | Rejected | Validation result; no Copy, no Save. |
| Validate | Accepted (with or without Warnings) | Validation result with Copy and *Save to library*. Copy here does **not** record usage, because there is no Saved Query. |
| Validate | 400 | Backstop only, because the client blocks over-limit input. The server message shows inline. |
| Edit | Loading | Skeleton form. |
| Edit | Default | The Save form prefilled, with the Edit notice. |
| Edit | 404 | The query was deleted in the meantime; show the detail 404 treatment. Local edits are lost; the SQL stays selectable on screen until the user leaves `[ASSUMPTION]`. |
| Edit | 409 conflict | **Merge on Reload.** Danger banner (message map) with *Reload*. *Reload* fetches the latest version and takes its `version`. Fields the user did not touch take the server values. Fields the user changed keep the user's values and get a "Your change" marker; if the server also changed that field, the marker reads "Your change — also changed by someone else" and shows the server value as text underneath. *Save changes* then saves against the new version. Satisfies Story 3.5. |
| Edit | Saved | Back to detail. Toast per the post-mutation rule. The badges reflect the re-validation. |
| Any | Unknown error, non-JSON body, or network failure | Generic message. |
| Any | First visit | The identity modal blocks the screen until a name is entered. |

## Interaction Primitives

- **Keyboard:**
  - Ctrl/⌘+Enter submits Generate, Validate, and the Save and Edit forms.
  - Enter in the Library search applies it.
  - Esc closes the topmost modal, menu, or listbox (not the first-visit identity modal).
  - Arrow keys work inside tabs, segmented controls, menus, and listboxes.
- **Focus:** every control shows the focus ring on `:focus-visible`. Focus order follows reading order.
  - When a modal opens, focus moves into it. When it closes, focus returns to the trigger.
  - After a Generate result, focus moves to the result region heading.
  - After a Validate run, focus moves to the result header.
- **Clipboard:** one click copies the full statement. If the browser denies access, the SQL is selected and the message reads "Couldn't copy — press Ctrl/⌘+C" `[ASSUMPTION]`.
- **Optimistic UI:** only the use count after Copy. Everything else waits for the server.
- **Navigation:**
  - Back and Forward restore Library filters and page from the URL, and Generate and Validate results from session state.
  - All in-module links stay in the same tab.
- **Banned:**
  - infinite scroll;
  - auto-save of Generated SQL;
  - auto-submit of a prefilled prompt;
  - hover-only affordances;
  - modals stacked more than one deep;
  - a schema-hint field;
  - Copy or Save on rejected SQL.

## Accessibility Floor

WCAG 2.2 AA. Contrast is handled in `DESIGN.md` § Colors; this section covers behavior.

- Each surface has one `h1` (page title). Each card title is an `h3` link.
- No status is conveyed by color alone. Badges, the validation result header (✕/✓ plus text), and the Warnings tab count all carry text.
- Result tabs include their counts in their accessible names ("Warnings, 1 item").
- Live regions:
  - *polite*: "Copied", toasts, the generating indicator text, Library result counts;
  - *assertive*: generation and validation errors, the 409 banner.
- Form errors are tied to their field with `aria-describedby`, and `aria-invalid` is set. Counters are linked as descriptions and aren't announced on every keystroke.
- ARIA patterns:
  - modals: `role="dialog"` with a labelled title, a focus trap, and focus restored on close;
  - user menu: menu button;
  - Replaced-by picker: combobox + listbox;
  - result tabs: tabs;
  - filter switch: switch;
  - segmented controls: radiogroup.
- SQL textareas have visible labels. The code-block line-number gutter and skeletons are `aria-hidden`.
- Every interactive element is reachable and operable by keyboard. Nothing is hover-only.
- Motion is limited to the ~2 s "Copied" swap and toast enter/exit, and respects `prefers-reduced-motion`.

## Inspiration & Anti-patterns

- **Taken from GitHub:** the **"GitHub list"** layout for the Library (picked over *Supabase split* and *Dense table*): full-width cards, inline content, a filter bar, and underline tabs. Pill badges for metadata.
- **Taken from the Supabase SQL editor:** a dark, recessed code surface as the visual center of gravity, and monospace for everything machine-shaped.
- **Taken from the *Dense table* direction (Generate only):** a command-bar prompt with result tabs. Its known weakness, that Warnings can hide behind a tab, is closed by **always-visible counts, a warning-colored label, and a Safety Note that sits outside the tabs**.
- **Rejected — Supabase split (editor + side panel) for the Library:** it hides the inline SQL that FR-27 requires and makes scanning slower.
- **Rejected — a schema-hint input:** Examples (team examples) replace it (FR-13; Won't v1).
- **Rejected — chat-style generation:** no multi-turn refinement (PRD §9). One prompt, one result.

## Key Flows

Illustrative SQL follows the seed schema in addendum §F: `orders(id, customer_id, region, total, status, created_at)`.

### UJ-1. Sami gets a draft at 11pm without opening a schema diagram.

Sami, a backend developer, is in the middle of a slow-production incident. The lead wants "orders per region last week vs the week before."

1. Sami opens DevAssist Hub → SQL. The display name was entered weeks ago, so the user chip reads "sami ▾".
2. Sami clicks the **Generate** tab and types the request into the command bar (47/1000). Sami reads the personal-data notice and presses Ctrl+Enter.
3. The button reads "Generating…", and the generating indicator shows.
4. The result lands on the SQL tab, with Examples (3) and Warnings (0), and the Safety Note below.
5. **Climax:** the SQL uses the team's real `orders` table, with `region`, `created_at`, and the team's "last week" idiom. Sami opens **Examples (3)**, sees *Weekly orders by region* among them, returns to the SQL tab, and clicks **Copy**.
6. Sami clicks **Save to library**. The Save form opens with the SQL and the original prompt carried over and the LLM-generated flag set. Sami types a title and one description line, then saves.
7. Sami lands on the new query's detail. Toast: "Saved · added to team examples".

**Failure:** the LLM is down. The banner shows the `LLM_UNAVAILABLE` message. Sami clicks **Save a query manually**. The Save form opens with the prompt already in Notes. Sami writes the SQL by hand and saves. If indexing is also down, the warning toast reads "Saved · not yet indexed (team examples unavailable)", and the save still succeeds.

### UJ-2. Leila inherits a query and knows whether to trust it.

Leila joined six months after the author of `weekly_region_orders` left. The manager needs the numbers for a board report.

1. In **Library**, Leila types "region orders" in the search. The URL updates, and the list narrows to two cards with their SQL inline.
2. One card shows *LLM-generated* and *Used 14× · last 2 weeks ago*. Leila clicks its title.
3. On **Query detail**, she reads the aside: description, author, the created and updated dates, Origin with the original prompt, Usage, *Read-only ✓*, and the note "Assumes created_at is UTC."
4. **Climax:** every signal she needs (well used, recent, read-only, with the author's caveat) is on one screen. She trusts it.
5. Leila clicks **Copy SQL**. The count goes to 15×, and she adjusts the date range in her own client.

**Variant:** if the query had been deprecated, the neutral banner would name the reason and link to *Weekly orders by region v2*. Leila would follow the link.

### UJ-3. Karim asks the question a second time and gets a better answer.

Karim is demoing the module to the course instructor.

1. On **Generate**, Karim submits the scripted question from outside the seeded e-commerce domain.
2. The result shows **Examples (0)** and **Warnings (1)** in warning color. Warnings holds the no-similar Warning, and the SQL uses invented table names.
3. Karim clicks **Save to library**, corrects the table names in the editable SQL, adds a title and description, and saves. Toast: "Saved · added to team examples".
4. Karim returns to **Generate** and submits the scripted follow-up question.
5. **Climax:** **Examples (1)** now lists the query Karim just saved, and the SQL uses the corrected table names. The instructor has watched team examples improve generation live.

**Failure:** the toast reads "Saved · not yet indexed (team examples unavailable)". The follow-up would come back without the new Example, so Karim calls the admin reindex endpoint (FR-25, `POST /api/sql/admin/reindex`; it is not in the UI) before asking the follow-up question.

### Flow 4 — Karim proves the validator in the demo

Karim, in the same 15-minute demo, has two minutes to show that the read-only check is real (addendum §G, scenario 4).

1. Karim clicks the **Validate** tab. The explainer sets expectations.
2. Karim pastes `WITH x AS (INSERT INTO audit_log …) SELECT …` and presses Ctrl+Enter. The result shows "✕ Rejected · 1 problem", then `DATA_MODIFYING_CTE` (or `SQL_PARSE_ERROR`, depending on the parser), the Normalized SQL, and the Safety Note.
3. Karim pastes three more attacks, one at a time:
   - `SELECT 1; ＤＲＯＰ ＴＡＢＬＥ orders` (fullwidth) → `MULTIPLE_STATEMENTS`;
   - `SELECT pg_read_file('/etc/passwd')` → `BLOCKED_FUNCTION`;
   - `SELECT * FROM orders FOR UPDATE` → `LOCKING_CLAUSE`.
4. **Climax:** Karim pastes the legitimate week-over-week report as a UNION ALL → "✓ Accepted · single read-only SELECT", with no Warnings. The check rejects attacks without over-rejecting real work (SM-C1).

**Failure:** the service is unreachable. The generic message shows in a danger banner and no result panel appears. Nothing is ever shown as accepted without a server response.

### Flow 5 — Sami sanity-checks a teammate's query from Slack

Sami, on a Tuesday afternoon, is about to run a query a teammate pasted in Slack against the reporting replica.

1. Sami opens **Validate**, pastes the query, and presses Ctrl+Enter.
2. "✕ Rejected · 1 problem": `LOCKING_CLAUSE`, with the reason naming `FOR UPDATE`.
3. Sami removes `FOR UPDATE` in the paste area and validates again.
4. **Climax:** "✓ Accepted · single read-only SELECT". The lock that would have stalled the replica is caught before it runs.
5. Sami clicks **Save to library**. The Save form opens with the normalized SQL. Sami adds a title and a description, saves, and lands on detail with "Saved · added to team examples".

**Failure:** the paste is over 20,000 chars. The counter turns red, the field is invalid, *Validate* is disabled, and nothing is sent.

### Flow 6 — Leila fixes a query and meets a conflict

Leila notices that `Weekly orders by region` counts cancelled orders, two days before the board report.

1. On the query's **detail**, Leila clicks **Edit**. The Edit notice explains that a SQL change re-checks read-only status.
2. She adds `AND status <> 'cancelled'` to the SQL and appends "excludes cancelled orders" to the description.
3. She clicks **Save changes**. A minute earlier, Sami saved a new tag on the same query.
4. The danger banner appears with *Reload*. She clicks it. The form now shows Sami's new tag, and her SQL and description keep their "Your change" markers.
5. **Climax:** she clicks **Save changes** again and lands on detail with both changes in place, *Read-only ✓*, and "Saved · added to team examples". Neither edit silently overwrote the other, and she retyped nothing.

**Failure:** her edit introduces an `UPDATE`. The save succeeds, but the toast reads "Saved", the detail shows the not-read-only banner with `NON_SELECT_STATEMENT`, and the query has dropped out of team examples.

### Flow 7 — Leila replaces an old query with v2

Leila has rewritten the regional report to use the new status codes, saved it as `Weekly orders by region v2`, and spotted a stray duplicate of the old one.

1. She opens the old query's **detail** and clicks **Deprecate**.
2. In the modal she types the reason "Uses old status codes" and types "v2" in *Replaced by*. The listbox offers *Weekly orders by region v2*, and she presses Enter, then **Deprecate**.
3. Toast: "Deprecated · removed from team examples". The detail shows the neutral banner with the reason and the v2 link, the *Deprecated* badge, and the **Undeprecate** action.
4. **Climax:** back in the **Library**, the old query is gone from the default view (Deprecated: Hide). The next person who generates a regional report is taught by v2, not the outdated query.
5. She opens the duplicate and clicks **Delete**. Focus lands on *Cancel*. She confirms, returns to the Library, and sees "Deleted 'Weekly orders by region (copy)'".

**Failure:** she tries to deprecate without a reason. *Deprecate* stays disabled, and the Reason field explains why. If v2 was deleted by someone else meanwhile, the 400 shows inline in the modal, which stays open.

### Flow 8 — Nour's first visit

Nour, a new intern, opens the SQL module on their first morning from a link to a Library search.

1. The Library renders behind the identity modal "What should we call you?". Esc does nothing, and focus is in the name field.
2. Nour types "nour" and presses Enter. *Continue* stores the name, and the modal closes.
3. **Climax:** the header chip reads "nour ▾", and the linked search is still in place. The next query Nour saves will carry their name as author.
4. A week later, Nour opens the user menu → *Change display name*, changes it to "nour.b", and selects *Continue*. Queries saved earlier keep the old author, because Author never changes.

**Failure:** Nour submits only spaces. *Continue* stays disabled.

## Open Items

| Item | Status |
|---|---|
| [UI] strings in the message map (quota, not configured, parse error, rejected, conflict, not found, generic) | Non-blocking. Confirm in frontend stories. |
| `[ASSUMPTION]` Raw model reply not shown on a 422 | Non-blocking; a possible later disclosure |
| `[ASSUMPTION]` Deprecation reason UI limit of 500 chars | Client-only and harmless; add a backend limit if wanted |
| `[ASSUMPTION]` Tags lowercased on entry | Confirm against seed tags |
| `[ASSUMPTION]` Toast position, search debounce, Tab-in-textarea, dimmed previous result, clipboard fallback, Edit 404 handling | Non-blocking interaction defaults |
| Mockup drift: Examples show the author, header *+ New query* is primary, the deprecated banner is accent, Reload discards | The spine wins; the mockup is not re-rendered |
