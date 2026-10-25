---
name: sql-service SQL module
description: Visual identity for the DevAssist Hub SQL module (Team Library, Generate, Validate). Midnight Terminal theme, dark only, desktop web, hand-rolled CSS on Angular 18 with no UI library.
status: final
created: 2026-10-18
updated: 2026-10-18
sources:
  - ../../prds/prd-sql-service-2026-10-18/prd.md
  - ../../prds/prd-sql-service-2026-10-18/addendum.md
  - ../../epics-sql-service.md
colors:
  # Surfaces: four tonal layers. Code is recessed below the page; cards and modals sit above it.
  code-bg: '#020712'
  bg: '#050b18'
  surface: '#0a1528'
  surface-raised: '#12223f'
  overlay: '#020712b3'          # modal scrim, code-bg at 70%
  # Lines
  border: '#28406a'             # decorative only (1.53:1 on surface-raised); never the only edge of a control
  input-border: '#5b77a3'       # every interactive control (3.48:1 on surface-raised, 4.01:1 on surface)
  # Text
  text-primary: '#f4f8ff'
  text-muted: '#a5b8d1'
  text-placeholder: '#8ea3bf'
  line-number: '#4f6789'        # decorative gutter, aria-hidden
  # Accent: the one interactive color
  accent: '#22d3ee'
  accent-hover: '#5ee0f3'
  accent-text: '#021219'
  accent-disabled: '#145f6c'
  accent-disabled-text: '#b8eef7'
  # Roles: fill and line colors
  success: '#4ade80'
  danger: '#ff6b86'
  danger-text-on-fill: '#1a0208'
  warning: '#facc15'
  info: '#b49cff'
  neutral: '#94a3b8'
  # Role text on tinted badges and inline errors, contrast-adjusted
  badge-danger-text: '#ff8aa0'
  badge-neutral-text: '#a5b4c8'
  # Tints: role color at 14% (badge fill), 40% (badge and banner line), 8% (banner and result-header fill)
  success-tint: '#4ade8024'
  success-line: '#4ade8066'
  success-wash: '#4ade8014'
  danger-tint: '#ff6b8624'
  danger-line: '#ff6b8666'
  danger-wash: '#ff6b8614'
  warning-tint: '#facc1524'
  warning-line: '#facc1566'
  warning-wash: '#facc1514'
  info-tint: '#b49cff24'
  info-line: '#b49cff66'
  neutral-tint: '#94a3b824'
  neutral-line: '#94a3b866'
  neutral-wash: '#94a3b814'
  accent-tint: '#22d3ee24'      # keyboard-highlighted option in pickers and menus
  # SQL syntax
  syntax-keyword: '#ff7ac6'
  syntax-identifier: '#e8f1ff'
  syntax-string: '#b5e655'
  syntax-number: '#fbbf24'
  syntax-function: '#22d3ee'
  syntax-comment: '#7690b0'
typography:
  page-title:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif'
    fontSize: 22px
    fontWeight: '600'
    lineHeight: '1.3'
  section-title:
    fontFamily: '{typography.page-title.fontFamily}'
    fontSize: 18px
    fontWeight: '600'
    lineHeight: '1.35'
  modal-title:
    fontFamily: '{typography.page-title.fontFamily}'
    fontSize: 16px
    fontWeight: '600'
    lineHeight: '1.4'
  card-title:
    fontFamily: '{typography.page-title.fontFamily}'
    fontSize: 15px
    fontWeight: '600'
    lineHeight: '1.4'
  body:
    fontFamily: '{typography.page-title.fontFamily}'
    fontSize: 14px
    fontWeight: '400'
    lineHeight: '1.5'
  control:
    fontFamily: '{typography.page-title.fontFamily}'
    fontSize: 13px
    fontWeight: '500'
    lineHeight: '1.4'
  label:
    fontFamily: '{typography.page-title.fontFamily}'
    fontSize: 13px
    fontWeight: '600'
    lineHeight: '1.4'
  meta:
    fontFamily: '{typography.page-title.fontFamily}'
    fontSize: 12px
    fontWeight: '400'
    lineHeight: '1.45'
  code:
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
    fontSize: 13px
    fontWeight: '400'
    lineHeight: '1.6'
  code-chip:
    fontFamily: '{typography.code.fontFamily}'
    fontSize: 12px
    fontWeight: '400'
    lineHeight: '1.4'
  counter:
    fontFamily: '{typography.code.fontFamily}'
    fontSize: 12px
    fontWeight: '400'
    lineHeight: '1.4'
rounded:
  sm: 4px
  md: 6px
  lg: 8px
  xl: 10px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 20px
  '6': 24px
  '7': 28px
  module-header-height: 52px
  content-x: 24px
  content-y: 20px
  form-max-width: 860px
  detail-aside-width: 300px
  sql-preview-lines: '6'
components:
  module-header:
    background: '{colors.surface}'
    border-bottom: '1px solid {colors.border}'
    height: '{spacing.module-header-height}'
    padding-x: '{spacing.5}'
  module-tab:
    foreground: '{colors.text-muted}'
    foreground-hover: '{colors.text-primary}'
    foreground-active: '{colors.text-primary}'
    indicator-active: '2px solid {colors.accent}'
    typography: '{typography.body}'
  breadcrumb:
    foreground: '{colors.text-muted}'
    link: '{colors.accent}'
    typography: '{typography.control}'
  button-primary:
    background: '{colors.accent}'
    background-hover: '{colors.accent-hover}'
    foreground: '{colors.accent-text}'
    border: '1px solid {colors.accent}'
    radius: '{rounded.md}'
    typography: '{typography.control}'
    padding: '6px 12px'
    background-disabled: '{colors.accent-disabled}'
    foreground-disabled: '{colors.accent-disabled-text}'
  button-secondary:
    background: '{colors.surface-raised}'
    border-hover: '1px solid {colors.accent}'
    foreground: '{colors.text-primary}'
    border: '1px solid {colors.input-border}'
    radius: '{rounded.md}'
    typography: '{typography.control}'
    padding: '6px 12px'
  button-danger:
    background: '{colors.danger}'
    foreground: '{colors.danger-text-on-fill}'
    border: '1px solid {colors.danger}'
    radius: '{rounded.md}'
  button-ghost:
    background: 'transparent'
    background-hover: '{colors.surface-raised}'
    foreground: '{colors.text-muted}'
    border: '1px solid transparent'
  button-ghost-danger:
    background: 'transparent'
    background-hover: '{colors.danger-wash}'
    foreground: '{colors.badge-danger-text}'
    border: '1px solid transparent'
  button-sm:
    padding: '3px 8px'
    fontSize: '{typography.meta.fontSize}'
  focus-ring:
    outline: '2px solid {colors.accent}'
    outline-offset: 2px
  text-input:
    background: '{colors.surface-raised}'
    foreground: '{colors.text-primary}'
    placeholder: '{colors.text-placeholder}'
    border: '1px solid {colors.input-border}'
    border-invalid: '1px solid {colors.danger}'
    radius: '{rounded.md}'
    padding: '6px 10px'
    typography: '{typography.control}'
  select:
    background: '{colors.surface-raised}'
    foreground: '{colors.text-primary}'
    border: '1px solid {colors.input-border}'
    chevron: '{colors.text-muted}'
    radius: '{rounded.md}'
    typography: '{typography.control}'
  sql-textarea:
    background: '{colors.code-bg}'
    foreground: '{colors.syntax-identifier}'
    border: '1px solid {colors.input-border}'
    border-invalid: '1px solid {colors.danger}'
    radius: '{rounded.md}'
    typography: '{typography.code}'
  field-counter:
    foreground: '{colors.text-muted}'
    foreground-over-limit: '{colors.badge-danger-text}'
    typography: '{typography.counter}'
  field-error:
    foreground: '{colors.badge-danger-text}'
    typography: '{typography.meta}'
  tags-input:
    container: '{components.text-input}'
    chip: '{components.tag}'
    chip-remove: '{colors.text-muted}'
  picker-listbox:
    background: '{colors.surface-raised}'
    border: '1px solid {colors.border}'
    radius: '{rounded.md}'
    option-active-background: '{colors.accent-tint}'
    option-foreground: '{colors.text-primary}'
    option-meta: '{colors.text-muted}'
    shadow: '0 8px 20px rgba(0,0,0,.4)'
  user-menu:
    background: '{colors.surface-raised}'
    border: '1px solid {colors.border}'
    radius: '{rounded.md}'
    item-active-background: '{colors.accent-tint}'
    shadow: '0 8px 20px rgba(0,0,0,.4)'
  query-card:
    background: '{colors.surface}'
    border: '1px solid {colors.border}'
    radius: '{rounded.lg}'
    padding: '14px 16px'
    gap-between: '{spacing.3}'
  skeleton-card:
    background: '{colors.surface}'
    border: '1px solid {colors.border}'
    bar: '{colors.surface-raised}'
    radius: '{rounded.lg}'
  code-block:
    background: '{colors.code-bg}'
    border: '1px solid {colors.border}'
    radius: '{rounded.lg}'
    padding: '12px 14px'
    typography: '{typography.code}'
    clip-fade: 'linear-gradient(transparent, {colors.code-bg}) 56px'
  badge:
    radius: '{rounded.full}'
    padding: '1px 8px'
    typography: '{typography.meta}'
    fontWeight: '500'
    border: '1px solid'
  badge-llm:
    foreground: '{colors.info}'
    background: '{colors.info-tint}'
    border-color: '{colors.info-line}'
  badge-not-read-only:
    foreground: '{colors.badge-danger-text}'
    background: '{colors.danger-tint}'
    border-color: '{colors.danger-line}'
  badge-deprecated:
    foreground: '{colors.badge-neutral-text}'
    background: '{colors.neutral-tint}'
    border-color: '{colors.neutral-line}'
  badge-read-only:
    foreground: '{colors.success}'
    background: '{colors.success-tint}'
    border-color: '{colors.success-line}'
  badge-usage:
    foreground: '{colors.text-muted}'
    background: '{colors.surface-raised}'
    border-color: '{colors.border}'
  tag:
    foreground: '{colors.text-muted}'
    background: '{colors.surface-raised}'
    border: '1px solid {colors.border}'
    radius: '{rounded.full}'
  code-chip:
    typography: '{typography.code-chip}'
    radius: '{rounded.sm}'
    padding: '1px 6px'
    rejection-foreground: '{colors.badge-danger-text}'
    rejection-background: '{colors.danger-tint}'
    warning-foreground: '{colors.warning}'
    warning-background: '{colors.warning-tint}'
  avatar:
    size: 22px
    background: '{colors.surface-raised}'
    foreground: '{colors.accent}'
    radius: '{rounded.full}'
  user-chip:
    border: '1px solid {colors.input-border}'
    radius: '{rounded.full}'
  banner:
    radius: '{rounded.lg}'
    padding: '10px 14px'
    border-warning: '1px solid {colors.warning-line}'
    background-warning: '{colors.warning-wash}'
    border-danger: '1px solid {colors.danger-line}'
    background-danger: '{colors.danger-wash}'
    border-neutral: '1px solid {colors.neutral-line}'
    background-neutral: '{colors.neutral-wash}'
  safety-note:
    background: '{colors.surface}'
    border: '1px solid {colors.border}'
    foreground: '{colors.text-muted}'
    lead-foreground: '{colors.text-primary}'
    radius: '{rounded.lg}'
  command-bar:
    background: '{colors.surface}'
    border: '1px solid {colors.border}'
    radius: '{rounded.xl}'
    padding: '{spacing.3}'
  generating-indicator:
    foreground: '{colors.text-muted}'
    typography: '{typography.meta}'
  result-tabs:
    foreground: '{colors.text-muted}'
    foreground-active: '{colors.text-primary}'
    indicator-active: '2px solid {colors.accent}'
    foreground-has-warnings: '{colors.warning}'
    panel-background: '{colors.surface}'
  examples-list:
    row-divider: '1px solid {colors.border}'
    title: '{colors.accent}'
  validation-result:
    header-rejected-foreground: '{colors.badge-danger-text}'
    header-rejected-background: '{colors.danger-wash}'
    header-accepted-foreground: '{colors.success}'
    header-accepted-background: '{colors.success-wash}'
    row-divider: '1px solid {colors.border}'
  modal:
    background: '{colors.surface-raised}'
    border: '1px solid {colors.border}'
    radius: '{rounded.xl}'
    max-width: 440px
    padding: 18px
    scrim: '{colors.overlay}'
    shadow: '0 20px 40px rgba(0,0,0,.5)'
  toast:
    background: '{colors.surface-raised}'
    border: '1px solid {colors.border}'
    accent-edge-success: '3px solid {colors.success}'
    accent-edge-warning: '3px solid {colors.warning}'
    radius: '{rounded.lg}'
    shadow: '0 8px 20px rgba(0,0,0,.4)'
  filter-switch:
    track-off: '{colors.surface-raised}'
    track-border: '1px solid {colors.input-border}'
    track-on: '{colors.accent}'
    knob-off: '{colors.text-muted}'
    knob-on: '{colors.accent-text}'
  segmented-control:
    border: '1px solid {colors.input-border}'
    segment-foreground: '{colors.text-muted}'
    segment-active-background: '{colors.surface-raised}'
    segment-active-foreground: '{colors.text-primary}'
    segment-active-indicator: 'inset 0 -2px 0 {colors.accent}'
  pager:
    page-border: '1px solid {colors.input-border}'
    page-active-background: '{colors.accent}'
    page-active-foreground: '{colors.accent-text}'
  empty-state:
    border: '1px dashed {colors.border}'
    radius: '{rounded.lg}'
---

## Brand & Style

The SQL module is a working tool for developers. Most of the time it is open during an incident or just before a report is due, so the visual posture is **Midnight Terminal**: a deep navy surface, crisp near-white text, and one electric cyan accent. It is sharp, high-contrast, and built for long sessions and fast scanning. The references are GitHub (list structure, underline tabs, pill badges) and the Supabase SQL editor (dark, recessed code surfaces). The tone is clean and minimal, with no decoration that doesn't carry information.

This identity covers the **SQL module only**. The DevAssist Hub shell is a bare `router-outlet` with no shared style, and the DevAssist Hub team owns the app-wide look, so every SQL screen carries its own slim module header. If the team ships a shell theme later, the module header is the seam to adapt. The rest of this spine stays as it is.

The product's main promise is *trust at a glance*: a stranger six months later has to be able to tell whether a query is read-only, generated, deprecated, or well used without opening it. That is why the identity spends its color budget on status badges and syntax highlighting, and almost none on chrome.

Dark only. There is no light mode in v1.

→ Visual reference: [`mockups/wireframes-all-screens.html`](mockups/wireframes-all-screens.html) shows all six screens and their states in this theme. If the mockup and this spine disagree, the spine wins.

## Colors

**Surfaces are four tonal steps, not shadows.** From deepest to highest: `{colors.code-bg}` (SQL lives *below* the page, like a terminal well), `{colors.bg}` (page), `{colors.surface}` (cards, module header, panels), and `{colors.surface-raised}` (inputs, secondary buttons, modals, menus, toasts). Hierarchy comes from these steps.

- **Accent cyan `{colors.accent}`** is the only interactive color: primary buttons, links, active-tab and active-segment indicators, the focus ring, the active pager page, keyboard-highlighted options (`{colors.accent-tint}`), and the avatar initial. **It never carries status.** Text on an accent fill is always `{colors.accent-text}` (10.5:1). A disabled primary button switches to `{colors.accent-disabled}` with `{colors.accent-disabled-text}` at full opacity, so it stays legible instead of fading out.
- **Role colors carry status, and each has one meaning.**
  - `{colors.info}` (violet): *LLM-generated* provenance.
  - `{colors.danger}`: *not read-only*, rejection, and destructive actions.
  - `{colors.warning}`: non-blocking Warnings and degraded service.
  - `{colors.success}`: *read-only ✓*, accepted, and saved.
  - `{colors.neutral}`: *deprecated* (withdrawn, not wrong).
- **Badge text uses contrast-adjusted variants** where the raw role color fails on its own tint: `{colors.badge-danger-text}` (lightened from #ff6b86, which measured only 4.82:1 on its tint over `surface-raised`, too close to the 4.5:1 floor) and `{colors.badge-neutral-text}` (lightened from #94a3b8, 4.87:1 on the same background). Tints and lines keep the original role color. Every badge text is ≥ 4.5:1 on its tint, over both `surface` and `surface-raised`.
- **Two border tokens with different jobs.** `{colors.border}` is decorative: card edges, dividers, tags. At 1.53:1 on `surface-raised` (1.90:1 on `bg`) it must never be the only edge of something clickable. `{colors.input-border}` outlines every interactive control and meets the 3:1 non-text contrast floor.
- **Syntax colors** are tuned for `{colors.code-bg}`: keywords pink, identifiers near-white, strings lime, numbers amber, functions cyan, and comments `{colors.syntax-comment}` in italic. All are ≥ 6.1:1. The function color reuses the accent hue on purpose. Inside code it never means "clickable", because code blocks have no inline links.

**Load-bearing contrast pairs (WCAG AA):**

| Pair | Ratio |
|---|---|
| `text-primary` on `bg` | 18.5:1 |
| `text-muted` on `bg` / `surface` / `surface-raised` | 9.7 / 9.0 / 7.8:1 |
| `accent-text` on `accent` | 10.5:1 |
| `danger-text-on-fill` on `danger` | 7.3:1 |
| `accent-disabled-text` on `accent-disabled` | 5.8:1 |
| `text-placeholder` on `surface-raised` | 6.1:1 |
| `syntax-comment` on `code-bg` | 6.1:1 |
| `input-border` on `surface-raised` / `surface` (non-text) | 3.48 / 4.01:1 |
| `accent` indicator on `surface-raised` (active segment, non-text) | 8.8:1 |
| Badge texts on their tints | 5.3–8.9:1 |

`{colors.line-number}` (3.5:1 on code-bg) is below the text floor. That is allowed only because line numbers are decorative and hidden from assistive tech.

## Typography

There are two families and no webfonts. The **UI** uses the platform system stack (`{typography.body}`, 14/1.5). **SQL and machine strings** use the platform monospace stack (`{typography.code}`, 13/1.6). "Machine strings" means Rejection Codes, character counters, and function names.

| Role | Token | Use |
|---|---|---|
| Page title | `{typography.page-title}` | One per screen: "Save to library", "Validate SQL", the query title on detail |
| Section title | `{typography.section-title}` | Rare; secondary headings inside a screen |
| Modal title | `{typography.modal-title}` | Dialog headings |
| Card title | `{typography.card-title}` | Query card titles in the Library (links, `{colors.accent}`) |
| Body | `{typography.body}` | Descriptions, banners, paragraphs, module tabs |
| Control | `{typography.control}` | Buttons, inputs, selects, breadcrumb |
| Label | `{typography.label}` | Form field labels |
| Meta | `{typography.meta}` | Author · updated line, badges, help text, inline errors, generating indicator |
| Code | `{typography.code}` | Every SQL surface, read-only or editable |
| Code chip | `{typography.code-chip}` | Inline Rejection Codes and function names |
| Counter | `{typography.counter}` | `0/200` field counters, so digits don't jitter |

Rules: SQL is **never** set in the UI face, not even a one-line excerpt. Rejection Codes always appear as code chips, never as prose. The description excerpt on a card is clamped to two lines.

## Layout & Spacing

The spacing scale is 4-based (`{spacing.1}`–`{spacing.7}`). Frames are designed at ~1280 px desktop width.

- **Module header:** full width, `{spacing.module-header-height}` tall. Left to right: the brand "DevAssist Hub / SQL", the module tabs (Library · Generate · Validate), a flexible spacer, *+ New query* (secondary), and the user chip.
- **Content area:** `{spacing.content-y}` × `{spacing.content-x}` padding.
- **Library and Generate** use the full content width. The Library is the "GitHub list": a single column of full-width query cards with `{spacing.3}` between them, a search row, then a filter bar, then cards, then the pager.
- **Forms (Save, Edit) and Validate** are centered at `{spacing.form-max-width}`, so line lengths stay readable and the SQL textarea doesn't run past what the eye can follow.
- **Query detail** uses two columns: the main column (breadcrumb, title, badges, action row, full SQL) and a `{spacing.detail-aside-width}` metadata aside.
- **SQL previews** on cards clip at `{spacing.sql-preview-lines}` lines, with a fade into `{colors.code-bg}` and a centered *Show all (n lines)* button.
- Below 900 px, the two-column Query detail layout stacks, and the module header scrolls horizontally. This is a graceful fallback, not a designed mobile surface.

## Elevation & Depth

Depth comes from **tonal layering first** (see Colors). Shadows are only for things that float above the page:

- **Modal:** `{components.modal.shadow}` over the `{colors.overlay}` scrim.
- **Toast, user menu, and picker listbox:** `{components.toast.shadow}`.

Cards, panels, the command bar, and code blocks have **no shadow**. They separate from the page by surface tone and a `{colors.border}` hairline. Code blocks read as *recessed*, because `code-bg` is darker than the page around them.

## Shapes

The corners are small and tool-like. The scale reads "terminal", not "consumer app":

- `{rounded.sm}` (4px): code chips.
- `{rounded.md}` (6px): buttons, inputs, selects, the segmented control, SQL textareas, menus, and listboxes.
- `{rounded.lg}` (8px): query cards, code blocks, banners, the Safety Note, toasts, empty states.
- `{rounded.xl}` (10px): modals and the Generate command bar, the two "containers you act inside".
- `{rounded.full}`: badges, tags, the avatar, the user chip, filter switches. Pills always mean *metadata or toggle state*. A button is never pill-shaped.

## Components

Every component is hand-rolled CSS. The module has no UI library. This section covers anatomy and token use. The strings, placement rules, and behavior for each component are in `EXPERIENCE.md` § Component Patterns, under the same names.

**Hover and press, globally:**
- Primary buttons go to `{colors.accent-hover}`.
- Secondary buttons and the user chip get a `{colors.accent}` border.
- Ghost buttons get a `{colors.surface-raised}` (or `{colors.danger-wash}`) fill.
- Links and card titles get an underline.
- Tabs get `{colors.text-primary}` text.
- There is no press transform and no transition longer than 120 ms.

| Component | Visual spec |
|---|---|
| **Module header** | `{components.module-header}`. Contains the **module tabs** (`{components.module-tab}`): underline tabs, muted text, and the active tab gets `{colors.text-primary}` with a 2px `{colors.accent}` underline. |
| **Breadcrumb** | `{components.breadcrumb}`: muted "Library › <title>" above the page title, with ancestor segments as accent links. |
| **Buttons** | *Primary* `{components.button-primary}`: one per surface, for the surface's commit action. *Secondary* `{components.button-secondary}`: all other actions, including header *+ New query*. *Danger fill* `{components.button-danger}`: only the confirm button in the delete modal. *Ghost-danger* `{components.button-ghost-danger}`: the Delete trigger on detail. *Ghost* `{components.button-ghost}`: tertiary actions. *Small* `{components.button-sm}`: Copy on cards, *Show all*. |
| **Focus ring** | `{components.focus-ring}` on every interactive element, on `:focus-visible` only. |
| **Text input** | `{components.text-input}`. Invalid → `border-invalid`, with **field error** (`{components.field-error}`) text underneath. |
| **Select** | `{components.select}` with a custom chevron. |
| **SQL textarea** | `{components.sql-textarea}`: monospace on `code-bg`, grows vertically. |
| **Field counter** | `{components.field-counter}`, right-aligned in the label row (`36/200`). It turns `foreground-over-limit` past the limit. |
| **Tags input** | `{components.tags-input}`: a text-input container holding **tag** chips, each with a muted × remove control. Free text is typed after the last chip. |
| **Replaced-by picker** | `{components.picker-listbox}`: a floating list under its input. The keyboard-active option gets `accent-tint`. Each option is a title plus a muted meta line. |
| **User chip and user menu** | `{components.user-chip}` contains the **avatar** (`{components.avatar}`, first initial) and "name ▾". It opens the `{components.user-menu}` popover. |
| **Query card** | `{components.query-card}`. Its anatomy, top to bottom: (1) title link (`card-title`, accent) with *Copy* (`button-sm`) on the right; (2) a two-line description excerpt; (3) the meta line: avatar, author, relative updated time; (4) tags, then badges; (5) the clipped **code block**. |
| **Skeleton card** | `{components.skeleton-card}`: query-card geometry with `surface-raised` bars for the title, two text lines, and a 6-line code area. It is static, with no shimmer animation. |
| **Code block** | `{components.code-block}`: read-only, syntax-highlighted SQL. On detail it has a line-number gutter (`{colors.line-number}`) and is not clipped. |
| **Badges** | `{components.badge}` base with `badge-llm`, `badge-not-read-only`, `badge-deprecated`, `badge-read-only` (detail only), and `badge-usage`. Every badge carries a text label, never color alone. |
| **Tag** | `{components.tag}`: a muted pill on `surface-raised`. |
| **Code chip** | `{components.code-chip}`: `rejection-*` colors for Rejection Codes, `warning-*` colors for function names in Warnings. |
| **Banner** | `{components.banner}`: an inline full-width notice with a leading mono glyph (`!`, `i`). Three variants: *warning* (degraded service, not-read-only on copy), *danger* (rejection, not-read-only reasons, 409 conflict, load failure), and *neutral* (deprecated, with the replacement pointer). |
| **Safety Note** | `{components.safety-note}`: muted body text with a bold "Safety note." lead. |
| **Command bar** | `{components.command-bar}`: the Generate container. A growing textarea on top; underneath, the personal-data notice (meta, muted) and a counter on the left, and *Generate* (primary) on the right. |
| **Generating indicator** | `{components.generating-indicator}`: one muted line under the command bar. |
| **Result tabs** | `{components.result-tabs}`: underline tabs with a count in each label. The warnings label switches to `foreground-has-warnings` when its count is above zero. The panel continues the `surface` with no top border. |
| **Examples list** | `{components.examples-list}`: one row per Example (accent title link), separated by hairlines. |
| **Validation result** | `{components.validation-result}`: a full-width header strip (rejected or accepted colors, with a ✕/✓ glyph and text), then reason rows (code chip plus plain-language message) separated by hairlines, then a code block holding the normalized SQL. |
| **Save form** | Labels (`{typography.label}`) above full-width text inputs and the SQL textarea. A *More options ▸* disclosure is a ghost button with a chevron that rotates ▸ → ▾. The action row is primary *Save* followed by secondary *Cancel*. |
| **Modal** | `{components.modal}`: title, body, and a right-aligned action row with Cancel first and the commit button last. Variants: Delete modal, Deprecate modal, Identity modal, Discard-changes modal. |
| **Toast** | `{components.toast}`: raised surface with a 3px colored left edge, success or warning. A single line of text, with no actions. |
| **Filter bar** | A wrapping row of a **tags input** (tag filter), a text input (author filter), a **filter switch**, two **segmented controls**, and a ghost *Clear filters* button. |
| **Filter switch** | `{components.filter-switch}`: a pill track, always outlined with `input-border`; the track turns accent when on. |
| **Segmented control** | `{components.segmented-control}`: the active segment gets `surface-raised`, primary text, and a 2px accent inset underline. |
| **Pager** | Range text on the left and page buttons on the right (`{components.pager}`). |
| **Empty state** | `{components.empty-state}`: a dashed box with one centered line of 15px text and up to two actions. |

## Do's and Don'ts

| Do | Don't |
|---|---|
| Use `{colors.accent}` only for things you can click, focus, or select | Use cyan for status, success, deprecation, or decoration |
| Give every status a text badge in its single role color | Signal read-only, deprecated, or LLM-generated with color or icon alone |
| Set all SQL in `{typography.code}` on `{colors.code-bg}` | Show SQL, even an excerpt, in the UI face or on a card surface |
| Outline every control (inputs, switches, segments, pager pages) with `{colors.input-border}` | Rely on `{colors.border}` (1.53:1) as the only edge of something clickable |
| Separate layers by surface tone | Add shadows to cards, panels, or code blocks |
| Keep the Safety Note visible beside every SQL result | Hide the Safety Note inside a tab, a tooltip, or a collapsed panel |
| Use one primary button per surface, for its commit action | Put two cyan buttons on the same screen, including the header |
| Use `badge-danger-text` / `badge-neutral-text` for text on tints | Put raw `{colors.danger}` or `{colors.neutral}` text on a tinted badge |
| Use pills for metadata and toggle state only | Shape a button like a pill |
| Show Rejection Codes as code chips beside plain English | Show a bare code, or plain English with no code |
