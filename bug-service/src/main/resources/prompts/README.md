# bug-service prompts

The triage feature (bug report → severity, category, suggested title as JSON) reads its prompts from
`prompts/<version>/`. `bug.triage.prompt-version` (env `BUG_PROMPT_VERSION`, default `v1`) selects the version,
and every triage result carries the `prompt_version` it was made with (stored in `bug.ai_prompt_version`).
To change a prompt, add a new version folder. Don't edit a released one, because stored results point at it.

## Files

| File | Role | Placeholders |
|---|---|---|
| `triage-system.txt` | System message: the task, the allowed values with their definitions, the output format and the safety rules | none |
| `triage-user.txt` | User message: the report inside `<report>…</report>` | `{{title}}`, `{{description}}`, `{{steps}}`, `{{expected}}`, `{{actual}}`, `{{environment}}`, `{{app_version}}`, `{{stack_trace}}` |
| `repair.txt` | Sent once when the first answer can't be used, after echoing that answer back as the assistant turn | `{{reason}}` |

An empty field is sent as `(not provided)`. The reporter and assignee are never sent.

## Pipeline (TriageService)

1. **Redaction** (`PiiRedactor`) runs on every field. It replaces private keys, JWTs, bearer tokens,
   `password=…`-style values and known API key formats (`hf_`, `sk-`, `ghp_`, …) with `[SECRET]` / `[TOKEN]`,
   credentials in URLs with `[SECRET]`, emails with `[EMAIL]`, public IPv4 addresses with `[IP]` (loopback is kept),
   international phone numbers with `[PHONE]`, and the user name in `C:\Users\…`, `/home/…` and `/Users/…` paths
   with `[USER]`. The number of replacements is returned to the UI as `redactions`.
2. **Trimming:** description 4000 chars, steps 2000, expected/actual 1000 each, stack trace the first 40 lines
   and 3000 chars, short fields 200.
3. **Model call** (`HfChatClient`): an OpenAI-compatible `POST {HF_BASE_URL}/chat/completions` with
   `temperature 0.1` and `max_tokens 300`.
4. **Validation** (`TriageParser`): the parser takes the first `{` to the last `}` (so a code fence, a `<think>`
   block or surrounding prose is tolerated) and parses it as JSON. `severity` and `category` must be the allowed
   values, ignoring case, with a few synonyms mapped (`major`→HIGH, `blocker`→CRITICAL, `minor`→LOW, `UI/UX`→UI, …).
   The title has quotes, a `Bug:` prefix and any trailing period stripped, must be at least 5 characters long, and is
   cut at a word boundary after 120. The rationale is optional and cut after 500.
5. **Repair:** if validation fails, the model gets one more turn with `repair.txt` saying what was wrong. If that
   answer fails too, the API answers `502 LLM_INVALID_OUTPUT`.

## Versions

### v1 (2026-10-25)
The first version. It uses a 4-level severity scale (LOW, MEDIUM, HIGH, CRITICAL) defined by user impact and
workaround, and 8 categories (UI, FUNCTIONAL, PERFORMANCE, SECURITY, CRASH, DATA, INTEGRATION, OTHER). For
reports that fit several categories, it applies a "root symptom" rule. It also includes a prompt-injection guard:
the report is data and placeholders are ignored.
