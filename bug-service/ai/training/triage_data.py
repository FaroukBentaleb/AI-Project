"""Turns Mozilla Bugzilla defects into bug-service triage examples. Standard library only.

Labels come from Bugzilla itself, not from a model:
- severity: Bugzilla's S1-S4 field -> CRITICAL / HIGH / MEDIUM / LOW
- category: derived from keywords (crash, sec-*, perf, dataloss, access...) and the component
- title:    the bug's summary line, as written by Mozilla triagers

The model input is built with bug-service's own prompt files (prompts/<version>/triage-system.txt and
triage-user.txt, inlined into the notebook), so the fine-tuned model sees at training time exactly what
TriageService sends at runtime.
"""
import json
import re

SEVERITY_MAP = {"S1": "CRITICAL", "S2": "HIGH", "S3": "MEDIUM", "S4": "LOW"}
SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
CATEGORIES = ("UI", "FUNCTIONAL", "PERFORMANCE", "SECURITY", "CRASH", "DATA", "INTEGRATION", "OTHER")
NOT_PROVIDED = "(not provided)"

MAX_DESCRIPTION = 2000   # TriageService sends up to 4000; shorter keeps training fast and inside the context
MIN_DESCRIPTION = 80

# Bot-filed or bookkeeping bugs whose text says nothing a user would write.
SKIP_KEYWORDS = {"intermittent-failure", "intermittent-testcase", "meta", "perf-alert"}
SKIP_SUMMARY = re.compile(r"^(intermittent|perma|\[meta\]|crash in \[@|tracking bug)", re.I)
SKIP_COMPONENTS = {"web-platform-tests", "Testing", "Mozilla Developer Network"}

_UI_COMPONENT = re.compile(r"layout|css|graphics|theme|toolbar|menus|tabbed browser|address bar|new tab|panel|"
                           r"widget|fonts|svg|scrolling|accessib|frontend|sidebar|\bui\b|tabs|reader mode|"
                           r"pdf viewer|printing|devtools|inspector|console", re.I)
_INTEGRATION_COMPONENT = re.compile(r"networking|sync|webextensions|services|remote settings|push|"
                                    r"migration|import|webauthn|payments|telemetry", re.I)


def category(bug: dict) -> str:
    """The first matching rule wins, from the most specific signal to the least."""
    keywords = set(bug.get("keywords") or [])
    component = bug.get("component") or ""
    summary = (bug.get("summary") or "").lower()
    if any(k.startswith(("sec-", "csectype-")) for k in keywords) or "security" in component.lower():
        return "SECURITY"
    if keywords & {"crash", "hang", "assertion"} or re.search(r"\b(crash|crashes|crashed|hangs?|freezes?)\b", summary):
        return "CRASH"
    if "dataloss" in keywords or re.search(r"\bdata ?loss\b|\b(lost|deleted|corrupt\w*)\b.*\b(data|bookmarks|"
                                           r"history|passwords|profile)\b", summary):
        return "DATA"
    if keywords & {"perf", "memory-leak", "power"} or any(k.startswith("perf:") for k in keywords) \
            or "performance" in component.lower() or re.search(r"\b(slow|slowness|jank|lag|cpu|memory leak)\b", summary):
        return "PERFORMANCE"
    if _INTEGRATION_COMPONENT.search(component):
        return "INTEGRATION"
    if keywords & {"access", "rtl"} or _UI_COMPONENT.search(component):
        return "UI"
    return "FUNCTIONAL"


_BOILERPLATE = re.compile(r"^(user[- ]agent|build identifier|build id|firefox for android|"
                          r"\(in reply to .*\))\s*:?.*$", re.I | re.M)
_REDACTIONS = [
    (re.compile(r"\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}"), "[TOKEN]"),
    (re.compile(r"(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}"), r"\1 [TOKEN]"),
    (re.compile(r"(?i)\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|token)(\s*[:=]\s*)"
                r"(\"[^\"]*\"|'[^']*'|[^\s,;&]+)"), r"\1\2[SECRET]"),
    (re.compile(r"(\b[a-zA-Z][a-zA-Z0-9+.-]*://)[^\s/:@]+:[^\s/@]+@"), r"\1[SECRET]@"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "[EMAIL]"),
    (re.compile(r"(?<![\d.])(?!127\.)(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}(?![\d.])"), "[IP]"),
    (re.compile(r"(?i)\b([A-Z]:\\(?:Users|Documents and Settings)\\)[^\\/\s:*?\"<>|]+"), r"\1[USER]"),
    (re.compile(r"(/(?:home|Users)/)[^/\s]+"), r"\1[USER]"),
]


def redact(text: str) -> str:
    """Same placeholders as bug-service's PiiRedactor, so training text looks like runtime text."""
    for pattern, replacement in _REDACTIONS:
        text = pattern.sub(replacement, text)
    return text


def clean_description(text: str) -> str:
    text = _BOILERPLATE.sub("", text.replace("\r\n", "\n"))
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    text = redact(text)
    if len(text) > MAX_DESCRIPTION:
        text = text[:MAX_DESCRIPTION] + " ... (truncated)"  # TriageService.truncate marks cuts the same way
    return text


def clean_title(summary: str) -> str:
    title = re.sub(r"\s+", " ", summary).strip()
    return re.sub(r"[.!]+$", "", title).strip()


def render(template: str, values: dict) -> str:
    """Mirror of PromptLibrary.render: replaces {{key}}, leaves unknown placeholders as they are."""
    return re.sub(r"\{\{\s*(\w+)\s*\}\}", lambda m: values.get(m.group(1), m.group(0)), template)


def build_messages(system_prompt: str, user_template: str, description: str, title: str = NOT_PROVIDED) -> list:
    values = {"title": title, "description": description, "steps": NOT_PROVIDED, "expected": NOT_PROVIDED,
              "actual": NOT_PROVIDED, "stack_trace": NOT_PROVIDED, "environment": NOT_PROVIDED,
              "app_version": NOT_PROVIDED}
    return [{"role": "system", "content": system_prompt.strip()},
            {"role": "user", "content": render(user_template.strip(), values)}]


def target_json(severity: str, category_: str, title: str) -> str:
    """The answer the model learns: compact, fixed key order (rationale is optional for TriageParser)."""
    return json.dumps({"severity": severity, "category": category_, "title": title}, ensure_ascii=False)


def make_example(bug: dict, first_comment: str):
    """A training example, or None when the bug is noise (bot-filed, too short, unknown severity...)."""
    severity = SEVERITY_MAP.get(bug.get("severity", ""))
    summary = bug.get("summary") or ""
    if severity is None or not summary or SKIP_SUMMARY.search(summary):
        return None
    if set(bug.get("keywords") or []) & SKIP_KEYWORDS or bug.get("component") in SKIP_COMPONENTS:
        return None
    description = clean_description(first_comment or "")
    title = clean_title(summary)
    if len(description) < MIN_DESCRIPTION or not 5 <= len(title) <= 120:
        return None
    return {"id": bug.get("id"), "description": description, "severity": severity, "category": category(bug),
            "title": title, "target": target_json(severity, category(bug), title)}


_ALIASES = {"BLOCKER": "CRITICAL", "MAJOR": "HIGH", "MINOR": "LOW", "TRIVIAL": "LOW", "NORMAL": "MEDIUM",
            "UX": "UI", "UI_UX": "UI"}


def parse_output(text: str):
    """Lenient reading of a model answer, like bug-service's TriageParser. Returns a dict or None."""
    text = re.sub(r"(?is)<think>.*?</think>", "", text or "")
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        data = json.loads(text[start:end + 1])
    except ValueError:
        return None
    if not isinstance(data, dict):
        return None

    def enum(value, allowed):
        key = re.sub(r"[^A-Z]+", "_", str(value or "").upper()).strip("_")
        key = _ALIASES.get(key, key)
        return key if key in allowed else None

    severity, category_ = enum(data.get("severity"), SEVERITIES), enum(data.get("category"), CATEGORIES)
    title = clean_title(str(data.get("title") or data.get("suggested_title") or ""))
    if severity is None or category_ is None or len(title) < 5:
        return None
    return {"severity": severity, "category": category_, "title": title}
