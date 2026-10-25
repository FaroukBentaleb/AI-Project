"""Prompt, diff trimming and output cleaning shared by training (Kaggle notebook) and serving (app.py).

Training and serving must build the model input identically, so both import (or inline) this file. Changing
anything here changes PROMPT_VERSION: retrain the adapter and bump the version.
"""
import re

PROMPT_VERSION = "v1"
# Same budget as commit-service's `commit.model.max-diff-chars`, so the Java side already sends at most this much.
MAX_DIFF_CHARS = 3500
CUT_MARKER = "\n... [diff truncated]"
COMMIT_TYPES = ("feat", "fix", "docs", "style", "refactor", "test", "chore")
MAX_SUBJECT_CHARS = 72

SYSTEM_PROMPT = (
    "You are a commit message generator. Given a unified diff, reply with a single Conventional Commits "
    "subject line in the form `type: short imperative description`. The type is one of "
    + ", ".join(COMMIT_TYPES)
    + f". Use lowercase, no trailing period, at most {MAX_SUBJECT_CHARS} characters. Reply with the subject line only."
)

_CONVENTIONAL = re.compile(r"^(" + "|".join(COMMIT_TYPES) + r")(\([\w./-]+\))?: \S.*$")


def trim_diff(diff: str, limit: int = MAX_DIFF_CHARS) -> str:
    """Cut to `limit` chars on a line boundary, marking the cut."""
    if len(diff) <= limit:
        return diff
    head = diff[: max(0, limit - len(CUT_MARKER))]
    newline = head.rfind("\n")
    if newline > 0:
        head = head[:newline]
    return head + CUT_MARKER


def build_messages(diff: str) -> list:
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": trim_diff(diff)},
    ]


def clean_message(text: str) -> str:
    """First non-empty line of a model reply, without quotes/backticks, bullets, or a trailing period."""
    for line in (text or "").splitlines():
        line = line.strip().strip("`\"'").strip()
        line = re.sub(r"^[-*]\s+", "", line)
        line = re.sub(r"\s+", " ", line).rstrip(".").strip()
        if line:
            return line[:100]
    return ""


def is_conventional(message: str) -> bool:
    return bool(_CONVENTIONAL.match(message)) and len(message) <= 100
