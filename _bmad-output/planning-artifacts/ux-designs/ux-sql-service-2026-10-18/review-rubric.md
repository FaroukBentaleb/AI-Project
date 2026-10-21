# Spine Pair Review — sql-service

## Overall verdict

**Adequate. Fix the high items before story-dev starts.** The pair is mechanically clean. Every `{token}` reference resolves, the contrast claims hold when recomputed, both section orders are canonical, the three UJ names are copied verbatim, and all six surfaces plus the identity modal are reachable from a Key Flow. The weak spots are behavioural and are where a story-dev agent would have to guess: Generate results disappear on in-app navigation, the 409 Reload contradicts Story 3.5's acceptance criteria, several controls have no spec in either spine, and a few UI promises are not backed by the API (Example author, filter option lists). None of the `[ASSUMPTION]` open items blocks work.

## 1. Flow coverage — strong

Checked: UJ-1, UJ-2, UJ-3 (PRD §2.3) against EXPERIENCE § Key Flows; the four extra flows approved in the memlog (Validate ×2, Edit ×2); each flow for a named protagonist, numbered steps, a **Climax** beat, and a failure path; and FR-27..FR-33 mapped to a flow step.

- UJ-1, UJ-2, UJ-3 headings match the PRD verbatim. Each has a named persona, numbered steps, and a marked climax. UJ-1 and UJ-3 have failure paths. UJ-2 has a deprecated *Variant*, which is fine for a read-only flow.
- Flows 4–7 each have a protagonist, steps, a climax, and a failure path.
- FR mapping: FR-27 (UJ-2 s1–2), FR-28 (UJ-2 s5; UJ-1 s5), FR-29 (UJ-1 s2–6), FR-30 (UJ-1 failure; Flow 4 failure), FR-31 (UJ-2 s3; Flows 6, 7), FR-32 (Flows 4, 5), FR-33 (UJ-1 s1, as backstory only).

### Findings
- **low** No flow walks **Delete**, **Undeprecate** (PRD "restore"), or the identity modal: neither the first visit nor *Change display name*. FR-31 delete and FR-33 appear only in the Component and State tables (EXPERIENCE § Key Flows; UJ-1 step 1 mentions the identity modal only as something that happened weeks ago). *Fix:* add one-line variant beats, for example in Flow 7 (Leila deletes a stray duplicate, sees the confirm and the toast) and in UJ-1 (first visit, the modal blocks, *Continue*). Alternatively, state that these are covered by § State Patterns only.
- **low** Flow 4 step 2 says the `WITH x AS (INSERT …)` fixture yields `DATA_MODIFYING_CTE`, but FR-11 allows `SQL_PARSE_ERROR` if the parser can't read it (EXPERIENCE Flow 4 s2). *Fix:* add "(or `SQL_PARSE_ERROR`, depending on the parser)" so the demo script and e2e tests don't over-assert.

## 2. Token completeness — strong

Checked: parsed the DESIGN.md frontmatter and resolved all 98 `{path}` references in DESIGN.md and the 3 real references in EXPERIENCE.md (the fourth match, `{path.to.token}`, is the explanatory placeholder in Foundation). **0 unresolved.** Every colour has a hex value; tints use 8-digit hex. Recomputed the WCAG ratios with Python, alpha-compositing tints over `surface` and `surface-raised`:

| Pair | Claimed | Computed |
|---|---|---|
| text-primary / bg | 18.5 | 18.47 ✓ |
| text-muted / bg · surface · raised | 9.7 / 9.0 / 7.8 | 9.72 / 9.02 / 7.82 ✓ |
| accent-text / accent | 10.5 | 10.53 ✓ |
| danger-text-on-fill / danger | 7.3 | 7.29 ✓ |
| accent-disabled-text / accent-disabled | 5.8 | 5.76 ✓ |
| text-placeholder / raised | 6.1 | 6.14 ✓ |
| syntax-comment / code-bg | 6.1 | 6.13 ✓ |
| input-border / raised · surface | 3.48 / 4.01 | 3.48 / 4.01 ✓ |
| badge texts on tints (both surfaces) | 5.3–8.9 | 5.33 (info/raised) – 8.90 (warning/surface) ✓ |
| raw danger on danger-tint / raised | 4.82 | 4.82 ✓ |
| line-number / code-bg | 3.5 | 3.49 ✓ |
| border | 1.53 | 1.53 on raised; 1.77 on surface; 1.90 on bg |

The syntax colours are not in the table but all pass, from 8.5:1 (keyword) to 17.7:1.

### Findings
- **medium** The **filter switch** off track (`surface-raised` on `surface`) is **1.15:1** and has no `input-border` outline. The **segmented control**'s active segment is also `surface-raised` on `surface` (1.15:1), so the selected segment differs only by a near-invisible fill and a muted→primary text change. Both break DESIGN's own Do "Outline every control with `{colors.input-border}`", and the switch is borderline under WCAG 1.4.11 (DESIGN frontmatter `filter-switch`, `segmented-control`; § Components "Filter switch and segmented control"). *Fix:* add `border: 1px solid {colors.input-border}` to the switch track. Give the active segment a ≥ 3:1 cue, such as an `input-border` or `accent` inset edge, or a `text-primary` 1px outline. Add both pairs to the contrast table.
- **low** Some tokens have no use or no prose: `colors.chrome-bar` is used nowhere; `components.module-tab` is never named in the prose; `components.badge-warning` and `badge-accent` are defined but not part of the badge set listed in § Components; and the `banner` *neutral* variant has no assigned use (DESIGN frontmatter; § Components "Banner"). *Fix:* delete them, or name where each is used.
- **low** The `border` ratio (1.53:1) doesn't name the surface it was measured on. It is `surface-raised`; cards sit on `bg`, where it is 1.90 (DESIGN § Colors). *Fix:* write "1.53:1 on surface-raised".

## 3. Component coverage — thin

Checked: every component named in either spine, the mockup, and the frontend stories, against a visual row in DESIGN § Components and a behavioural row in EXPERIENCE § Component Patterns, with names compared across the two.

| Component | DESIGN | EXPERIENCE | Note |
|---|---|---|---|
| Module header, Query card, Code block, SQL textarea, Badges, Command bar, Result tabs, Safety Note, Toast, Banner, Pager | ✓ | ✓ | names match |
| Validation result | "Validation result header" | "Validation result" | name drift |
| Modal | "Modal" | "Deprecate / Delete / Identity modal" | acceptable split, but the generic name differs |
| Text input and select | ✓ | "Search input", "Sort select" | name drift |
| Filter bar | only "Filter switch and segmented control" | "Filter bar" | name drift; tag and author controls missing |
| Examples list | — | ✓ | **no visual spec** |
| Save form | layout prose only | ✓ | no component row in DESIGN |
| Unsaved-changes guard | — | ✓ | no visual; dialog type unknown |
| Code chip, Avatar / user chip, Empty state, Field counter / error, Generating indicator, Buttons | ✓ | — (folded into others) | acceptable for code chip and buttons; Empty state and Generating indicator deserve rows |
| Tags input (Save/Edit *More options*) | — | — | **missing in both** |
| Tags multi-select and Author filter | — | — (one line in Filter bar) | **missing in both** |
| Replaced-by picker (Deprecate modal) | — | — (one clause) | **missing in both** |
| User-chip menu, Breadcrumb, Skeleton card, *More options* disclosure | — | mentioned only | missing rows |

### Findings
- **medium** Several controls that stories must build are specified in neither spine. The **Tags input** needs entry model (chips or comma-separated), the 0–10 × ≤ 50-char limits, and its error states. The **Replaced-by picker** needs combobox behaviour, a min-chars rule, empty and loading states, and keyboard handling. Also missing are the **Tag multi-select and Author filter** controls, the **user-chip menu**, the **breadcrumb**, the **skeleton card**, and the ***More options* disclosure** (EXPERIENCE § Component Patterns rows Save form, Deprecate modal, Filter bar, Module header; DESIGN § Components). *Fix:* add a row to both spines for each. For the picker and the tags input, specify the ARIA pattern (combobox / listbox), the keys, and the limits.
- **medium** **Unsaved-changes guard**: the spine doesn't say whether it is a custom `modal` or `window.confirm`. A custom modal could stack on another modal when Edit's *Reload* asks for confirmation, and § Interaction Primitives bans stacking more than one deep (EXPERIENCE § Component Patterns "Unsaved-changes guard"; State Patterns "Edit 409"). *Fix:* commit to the `{components.modal}` styling with copy ("Discard your changes?" · Cancel / Discard), and use `beforeunload` only for tab close.
- **low** Several names differ between the spines: "Validation result header" vs "Validation result", "Text input and select" vs "Search input" / "Sort select", "Filter switch and segmented control" vs "Filter bar". *Fix:* use the same row names in both spines. Add "Examples list" and "Save form" to DESIGN, and "Empty state", "Generating indicator", and "Code chip" to EXPERIENCE.

## 4. State coverage — adequate

Checked: each IA surface (00–06) against loading, empty, success, error, 404, submitting, and focus states; the mockup's state panels; and the PRD §5 error codes (`VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`, the three 422s, the three 503s, and the gateway timeout).

- Library, Generate, Validate, and Save are well covered. All three 422 codes, all three 503 codes, the gateway timeout, and the generic error are covered, and every mockup state panel has a matching row.
- Gaps: `NOT_FOUND` is handled only for detail view, not for edit, delete, deprecate, or usage. `VALIDATION_FAILED` is not handled on deprecate.

### Findings
- **medium** These states are missing: **Detail loading**; **Edit loading** and **Edit 404** (the query was deleted while being edited, so PATCH returns 404); the **submitting and failure** states of **Deprecate, Undeprecate, and Delete** (a 400 when the replacement was deleted in the meantime, a 404 when the query is already deleted); the **toast copy** for delete success and for undeprecate; and **usage-call failure** after the optimistic count bump. Story 3.3 says a usage failure "never blocks the copy", but the spine doesn't say whether the count rolls back (EXPERIENCE § State Patterns). *Fix:* add rows. Suggested: a skeleton aside for detail loading; Edit 404 → the detail 404 page; modal errors as inline danger text with the modal kept open; delete 404 → back to Library with a "Already deleted" toast; usage failure → keep the count and log silently.
- **medium** Client-side limits are not committed for every field: SQL ≤ 20,000 in Save and Edit, tags 0–10 × ≤ 50, and display name ≤ 100 (`author varchar(100)`, addendum §B). Validate also contradicts itself. State Patterns says "400 (over 20,000 chars): Inline under the paste area", which implies a server round-trip, while Flow 5's failure path says "nothing is sent" (EXPERIENCE § State Patterns Validate 400; Flow 5 failure; Component Patterns Identity modal). *Fix:* add a limits line to the Save form, Validate, and Identity rows that mirrors the API. Disable *Validate* above 20,000 the same way *Generate* is disabled above 1,000, and keep a server-400 mapping as the backstop.
- **medium** The post-save toast logic is underspecified, and the obvious reading gives a wrong answer. "Saved, not indexed → warning toast 'not yet indexed (team examples unavailable)'" invites keying the toast on `indexed = false`. But editing a **deprecated** query also legitimately returns `indexed = false`, and Edit uses "the same toast variants as Save" (EXPERIENCE § State Patterns Save rows, Edit "Saved"). *Fix:* key the warning toast on the `warnings[]` array the 201 or 200 response carries (addendum §B). Toast precedence: warnings present → warning toast; not read-only → "Saved"; deprecated → "Saved"; otherwise "Saved · added to team examples".
- **low** For an `SQL_REJECTED` result on Generate, the spine doesn't say whether the result tabs (Examples / Warnings) still render. The ARIA tab activation mode (automatic vs manual) is also not stated (EXPERIENCE § State Patterns Generate `SQL_REJECTED`; Component Patterns Result tabs). *Fix:* state that the tabs are hidden on rejection, and that activation is automatic on arrow keys.
- **low** The save-success toast offers *View* even though the user has already landed on detail (EXPERIENCE § State Patterns "Saved and indexed"). *Fix:* drop *View* on save toasts and keep it for cases where the user doesn't navigate.

## 5. Visual reference coverage — strong

Checked: `mockups/` has 1 file, `wireframes-all-screens.html`. `wireframes/` does not exist. `imports/` is empty. `.working/` holds three exploration files (out of scope, non-normative).

- `mockups/wireframes-all-screens.html` is linked inline in DESIGN § Brand & Style ("all six screens and their states in this theme") and in EXPERIENCE § Foundation ("every surface below").
- The spine-wins-on-conflict rule is stated once in each spine.
- Orphans: none in the normative folders.

### Findings
- **low** EXPERIENCE § Inspiration names the rejected "Supabase split" and "Dense table" directions, and DESIGN names the chosen theme, all from `.working/layouts-1.html` and `.working/color-themes-1.html`. Neither file is linked or marked as exploration. The mockup still carries the title "round 1" and some notes the spine has since corrected (for example "FR30 Tabs show counts", "FR34 helper text", ids shaped like `q_8f21c4` where the API uses bigserial). *Fix:* add one line saying `.working/` is non-normative exploration, optionally linking it. Rely on the existing spine-wins rule for the mockup's annotations.

## 6. Bloat & overspecification — strong

Checked: section length against the examples, duplication between the spines, and frontmatter scope. The size is in proportion to a hand-rolled, no-library module. The long State Patterns table is earned, because the error matrix is the contract. The frontmatter is complete without padding.

### Findings
- **low** DESIGN § Components carries behaviour and copy that EXPERIENCE owns: "Rejected · n problem(s)" and "Accepted · single read-only SELECT", the Generating indicator wording, "always outside the tabs" for the Safety Note, and the Warnings label rule. Two copies will drift apart (DESIGN § Components "Validation result header", "Safety Note", "Generating indicator", "Result tabs"). *Fix:* in DESIGN keep only the anatomy and token use, and point to EXPERIENCE for the strings and placement rules.
- **low** DESIGN gives no hover or active states for buttons, cards, links, tabs, or pager pages. Each story will invent its own (DESIGN § Components). *Fix:* add one rule, for example "hover: background shifts one tonal step / accent at 90%; active: no transform."

## 7. Inheritance discipline — adequate

Checked: the source paths resolve (`../../prds/.../prd.md` and `../../epics-sql-service.md` both exist). Note that DESIGN.md does not list `addendum.md` as a source even though both spines depend on its shapes. Also checked: UJ names verbatim (✓), FR numbers in the IA table (✓), glossary usage, EXPERIENCE token references (✓), and factual drift against the PRD, the addendum, and the epics.

### Findings
- **high** The **Generate (and Validate) result is lost on in-app navigation.** Results are not in the URL ("A reload clears them"), Example links "navigate in the same tab", and the Generate→Save handoff mechanism isn't defined. So clicking an Example during UJ-1 step 5, or pressing *Cancel* or Back on the Save form, discards Sami's generated SQL, which makes the flagship flow fragile. This is not PRD drift; it sits here because it is an uncommitted cross-surface contract (EXPERIENCE § Information Architecture "URL state"; § Interaction Primitives "Navigation"; § Component Patterns "Examples list"). *Fix:* keep the last prompt and result per surface in a module-scoped service that survives route changes and is cleared on a new run or a reload. Pass the Save prefill through that service or router state, and return to Generate with the result intact on *Cancel*. Alternatively, open Example links in a new tab.
- **high** The **409 Reload discards local edits**, which contradicts **Story 3.5's AC** "can reload without losing my edits from view". The spine's own Flow 6 forces Leila to hand-copy her SQL line before reloading (EXPERIENCE § State Patterns "Edit 409 conflict"; Flow 6 s5; epics Story 3.5). *Fix:* commit to one behaviour. Preferred: *Reload* refetches the server version, takes the new `version`, and re-applies the locally dirty fields on top, highlighting any field changed on both sides. Otherwise, explicitly amend the Story 3.5 AC.
- **medium** The Examples list shows **title + author**, and UJ-1 step 5 relies on "*Weekly orders by region* by sami". But `examples_used` is `{id, title}` only (PRD FR-18; addendum §B) (EXPERIENCE § Component Patterns "Examples list"; UJ-1 s5). *Fix:* either drop the author, or log a contract change adding `author` to `examples_used` items in addendum §B and in Story 6.4 / 7.1.
- **medium** The Safety Note says "The Safety Note text is fixed (FR-18)", but the spine never gives the text or its source. FR-18 says "reads, in substance", and the mockup shows **two different texts**: Generate "Verified to be a single read-only SELECT structure…" and Validate "Structural check only…". The Generate text would be false on a rejected Validate result (EXPERIENCE § Voice and Tone; § Component Patterns "Safety Note"). *Fix:* commit to "render `safety_note` from the response verbatim (generate and validate both return it); the UI holds no copy of it", or pin both exact strings and say which outcome uses which.
- **medium** The error-copy policy is not committed. Voice says "Errors use the PRD's exact messages", yet the spine writes its own client strings for `LLM_QUOTA_EXCEEDED`, `LLM_NOT_CONFIGURED`, `SQL_PARSE_ERROR`, and `SQL_REJECTED`, extends the PRD's `LLM_REFUSED` text with "Try rephrasing.", and uses "the server message" for 400. Meanwhile FR-30 and Story 3.1 say to show "the known code's message" (EXPERIENCE § Voice and Tone bullet 2; § State Patterns Generate rows). *Fix:* add one rule: "A client map keyed by `error` gives the UI string for the codes listed here (that map is the contract). Otherwise show the server `message`. Otherwise show the generic message." Tag each string as PRD-exact or UI-authored.
- **medium** **Story 7.3** says *Save a query manually* opens the form "with the prompt prefilled in notes". The spine says it opens "a blank Save form" (EXPERIENCE § State Patterns `LLM_UNAVAILABLE`; IA row 03; epics Story 7.3). *Fix:* pick one (prefilling notes with the prompt is cheap and useful). Add a Foundation line saying the spines supersede the epics' frontend behaviour, and list the deltas: this one, the 6-line SQL clip vs Story 3.2's "scroll if long", and the 409 Reload.
- **medium** The Tags multi-select and Author filter, drawn as dropdowns in the mockup ("Tags: reporting, orders ▾", "Author: anyone ▾"), have **no option source**. PRD §5 has no tags or authors list endpoint (EXPERIENCE § Component Patterns "Filter bar"). *Fix:* commit to free-text entry (a tag chip input, an author text field) that maps to `tags=` and `author=`, or add a facets endpoint to the backlog and mark it as a dependency.
- **low** The IA Closure says "FR-34 is represented only by the Description helper text", and the mockup labels the helper text "FR34". FR-34 is *similar queries at save time*, and helper copy doesn't realise it (EXPERIENCE § Information Architecture "Closure"). *Fix:* "FR-34 and FR-35 (Could) have no v1 surface." Treat the helper text as plain copy.
- **low** Glossary drift: the PRD's "restore" (FR-7) appears in the UI as *Undeprecate*, but the IA row 02 says "deprecate or restore" and the glossary table has no restore→Undeprecate row (EXPERIENCE § Voice and Tone glossary; IA row 02). *Fix:* add the mapping and use *Undeprecate* consistently.
- **low** Foundation says "The gateway timeout on generation is 15 s". The route-level timeout covers all of `/api/sql/**` (PRD §6 API gateway). *Fix:* "The `/api/sql/**` route timeout is 15 s (22 s under Plan B); generation is the only call that approaches it."
- **low** Example SQL in UJ-1 and the mockup uses `regions r`, `o.total_amount`, and `is_test`. The addendum §F seed schema has `orders.region`, `total`, and no `is_test`. The sources conflict themselves (PRD §4.5 lists "regions"). *Fix:* align the illustrative SQL with addendum §F so demo scripts and e2e fixtures copied from the spine actually run.

## 8. Shape fit — strong

Checked: DESIGN.md body order: Brand & Style → Colors → Typography → Layout & Spacing → Elevation & Depth → Shapes → Components → Do's and Don'ts ✓ (canonical, nothing out of order). EXPERIENCE.md required defaults: Foundation ✓, Information Architecture ✓, Voice and Tone ✓, Component Patterns ✓, State Patterns ✓, Interaction Primitives ✓, Accessibility Floor ✓, Key Flows ✓. Triggered sections: Inspiration & Anti-patterns (earned; records the approved layout and theme choices and the schema-hint ruling) and Open Items (earned; indexes the `[ASSUMPTION]` tags). Omitting Responsive & Platform is correct for a desktop-only product, and the fallback is stated in Foundation and in DESIGN § Layout.

### Findings
- **medium** The rule "one primary button per surface" is broken on every screen. *+ New query* in the module header is specified as **primary**, so Library, Generate (*Generate*), Validate (*Validate*), Save (*Save*), and Edit (*Save changes*) all show two cyan buttons. That contradicts DESIGN's Do "One primary button per surface" and its Don't "Two cyan buttons competing on the same screen" (DESIGN § Layout "Module header", § Components Buttons, § Do's and Don'ts). *Fix:* make the header's *+ New query* `button-secondary` (or a ghost with accent text), or state the header as an explicit exception.
- **medium** Banner "info" variant: it uses the **accent** wash and line for *deprecated* status. This contradicts "Accent cyan is … never used for status" and "neutral: deprecated", and the variant's name collides with `colors.info`, which is violet and means LLM-generated (DESIGN § Components "Banner"; § Colors). *Fix:* render the deprecated banner with the `neutral` wash and line (its role colour) and rename the variant (for example `banner-notice`), or document the carve-out explicitly in § Colors.
- **low** EXPERIENCE.md frontmatter uses `title:` where the example spine uses `name:`, and DESIGN.md `sources` omits `addendum.md`. *Fix:* align the keys, and add the addendum as a source in both spines.

## Mechanical notes

- Token references: DESIGN 98 of 98 resolve; EXPERIENCE 3 of 3 resolve (`{path.to.token}` is the explanatory placeholder). Every colour token has a hex value.
- Every contrast claim was recomputed and matches to two decimals. The only gaps are the switch and segmented-control state pairs (1.15:1, see §2).
- UJ headings are verbatim to PRD §2.3. FR numbers in the IA table are correct.
- `[ASSUMPTION]` triage: **none blocking.** The deprecation-reason 500 counter is a client-only limit and harmless whether or not the backend matches. The others are copy or interaction defaults.
- Wireframe parity: every mockup state panel has a State Patterns row, except two places where the mockup is missing a state rather than the spine (detail and edit loading).
- Finding counts: **critical 0 · high 2 · medium 13 · low 15**.
