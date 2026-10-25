"""CommitPackFT rows -> (diff, Conventional Commits subject) training pairs. Pure standard library.

The diff text mirrors what commit-service's DiffBuilder sends: a `diff --git` header, `--- a/...` / `+++ b/...`
(`/dev/null` for an added or deleted file), hunks with 3 lines of context and explicit counts (`@@ -1,2 +1,2 @@`).
"""
import difflib
import posixpath
import re

from commit_prompt import COMMIT_TYPES, MAX_SUBJECT_CHARS, is_conventional

MAX_CONTENT_CHARS = 30_000
BINARY_SUFFIXES = (".lock", ".min.js", ".min.css", ".map", ".svg", ".ipynb", ".csv", ".tsv", ".dat")

_HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
_TYPED = re.compile(r"^(feat|fix|docs|style|refactor|test|chore|perf|build|ci)(\([^)]*\))?!?:\s*(.+)$", re.I)
_TYPE_ALIASES = {"perf": "refactor", "build": "chore", "ci": "chore"}

_DOC_EXTS = {".md", ".markdown", ".rst", ".txt", ".adoc", ".textile"}
_STYLE_EXTS = {".css", ".scss", ".sass", ".less", ".styl"}
_VERBS = {
    "fix": {"fix", "fixes", "fixed", "fixing", "bugfix", "hotfix", "correct", "corrects", "corrected", "resolve",
            "resolves", "repair", "patch"},
    "feat": {"add", "adds", "added", "adding", "implement", "implements", "implemented", "create", "creates",
             "created", "introduce", "introduces", "support", "supports", "enable", "enables", "allow", "allows",
             "new", "feature"},
    "refactor": {"refactor", "refactors", "refactored", "restructure", "rename", "renames", "renamed", "move",
                 "moves", "moved", "simplify", "simplifies", "cleanup", "clean", "reorganize", "extract",
                 "rewrite", "replace", "replaces", "optimize", "improve", "improves"},
    "docs": {"doc", "docs", "document", "documents", "documentation", "readme", "typo", "comment", "comments"},
    "test": {"test", "tests", "testing", "spec", "specs"},
    "style": {"style", "styles", "format", "formats", "formatting", "lint", "indent", "indentation", "whitespace",
              "prettify", "beautify"},
    "chore": {"bump", "upgrade", "release", "version", "remove", "removes", "removed", "delete", "deletes",
              "deleted", "drop", "revert", "ignore", "gitignore", "config", "update", "updates", "updated",
              "change", "changes", "changed", "modify", "tweak", "merge"},
}
_VERB_TO_TYPE = {verb: kind for kind, verbs in _VERBS.items() for verb in verbs}


def _fix_hunk_counts(line: str) -> str:
    """difflib omits ',1' in hunk headers; the Java side always writes counts."""
    m = _HUNK.match(line)
    if not m:
        return line
    old_start, old_len, new_start, new_len = m.groups()
    return f"@@ -{old_start},{old_len or 1} +{new_start},{new_len or 1} @@" + line[m.end():]


def build_diff(old_file: str, new_file: str, old_contents: str, new_contents: str) -> str:
    old_file, new_file = old_file or new_file, new_file or old_file
    old_lines = (old_contents or "").replace("\r\n", "\n").splitlines()
    new_lines = (new_contents or "").replace("\r\n", "\n").splitlines()
    lines = list(difflib.unified_diff(
        old_lines, new_lines,
        fromfile="/dev/null" if not old_lines else f"a/{old_file}",
        tofile="/dev/null" if not new_lines else f"b/{new_file}",
        n=3, lineterm=""))
    if not lines:
        return ""
    return "\n".join([f"diff --git a/{old_file} b/{new_file}"] + [_fix_hunk_counts(line) for line in lines])


def _is_test_path(path: str) -> bool:
    p = path.lower()
    name = posixpath.basename(p)
    parts = p.split("/")
    return (any(part in ("test", "tests", "spec", "specs", "__tests__") for part in parts[:-1])
            or name.startswith("test_") or name.endswith(("_test.py", "_test.go", ".test.js", ".test.ts",
                                                           ".spec.js", ".spec.ts", "test.java", "tests.java")))


def classify(subject: str, paths: list) -> str:
    """Conventional Commits type for a free-form subject, from its leading verb and the files touched."""
    first = re.sub(r"[^a-z]", "", subject.split(" ", 1)[0].lower())
    by_verb = _VERB_TO_TYPE.get(first)
    exts = {posixpath.splitext(p.lower())[1] for p in paths if p}
    if by_verb == "fix":
        return "fix"
    if paths and all(_is_test_path(p) for p in paths):
        return "test"
    if exts and exts <= _DOC_EXTS:
        return "docs"
    if by_verb and by_verb != "chore":
        return by_verb
    if exts and exts <= _STYLE_EXTS:
        return "style"
    return "chore"


def conventional_subject(subject: str, paths: list) -> str:
    """'Add about page.' -> 'feat: add about page'. Already-typed subjects keep their type."""
    s = re.sub(r"\s+", " ", (subject or "").strip())
    s = re.sub(r"\s*\(#\d+\)$", "", s).rstrip(".").strip()
    m = _TYPED.match(s)
    if m:
        kind = m.group(1).lower()
        kind = _TYPE_ALIASES.get(kind, kind)
        desc = m.group(3).strip()
    else:
        kind, desc = classify(s, paths), s
    if len(desc) > 1 and not desc[:2].isupper():
        desc = desc[0].lower() + desc[1:]
    return f"{kind}: {desc.rstrip('.')}"


def keep_row(row: dict) -> bool:
    subject = (row.get("subject") or "").strip()
    old, new = row.get("old_contents") or "", row.get("new_contents") or ""
    path = (row.get("new_file") or row.get("old_file") or "").lower()
    if not 8 <= len(subject) <= 100 or old == new or (not old and not new):
        return False
    if re.match(r"^(merge|revert|initial commit|update readme$)", subject, re.I):
        return False
    if sum(ch.isascii() for ch in subject) < 0.95 * len(subject):  # keep English subjects
        return False
    if len(old) > MAX_CONTENT_CHARS or len(new) > MAX_CONTENT_CHARS or path.endswith(BINARY_SUFFIXES):
        return False
    return True


def make_example(row: dict):
    """One training pair, or None when the row is unusable."""
    if not keep_row(row):
        return None
    paths = [row.get("new_file") or row.get("old_file") or ""]
    target = conventional_subject(row["subject"], paths)
    if not is_conventional(target) or len(target) > MAX_SUBJECT_CHARS:
        return None
    diff = build_diff(row.get("old_file", ""), row.get("new_file", ""), row.get("old_contents", ""),
                      row.get("new_contents", ""))
    if len(diff) < 40:
        return None
    return {"diff": diff, "target": target, "lang": row.get("lang", "")}


assert set(_VERB_TO_TYPE.values()) <= set(COMMIT_TYPES)
