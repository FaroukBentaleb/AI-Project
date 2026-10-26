"""CodeSearchNet rows -> (code, docstring) training pairs. Pure standard library.

CodeSearchNet quirk: for Python, `func_code_string` still contains the docstring (the answer!), so it is removed
here with `ast`. For Java/JavaScript the doc comment is already stored apart from the code.
"""
import ast
import re
import textwrap
import warnings

from snippet_prompt import LANGUAGES, MAX_CODE_CHARS, is_valid_docstring

MIN_CODE_CHARS = 40
_BOILERPLATE = re.compile(
    r"(\{@inheritDoc\}|auto-?generated|\bTODO\b|\bFIXME\b|^\s*@(override|see|deprecated)\b|^\s*(get|set)s? the\b.{0,30}$)",
    re.I)
_TEST_NAME = re.compile(r"(^|[._])(test|tests|spec)", re.I)


def strip_python_docstring(code: str):
    """Code without the function's docstring, or None when it cannot be parsed or has no docstring."""
    try:
        with warnings.catch_warnings():  # scraped code is full of invalid escape sequences
            warnings.simplefilter("ignore")
            tree = ast.parse(textwrap.dedent(code))
    except (SyntaxError, ValueError):
        return None
    function = next((n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))), None)
    if function is None or not function.body:
        return None
    first = function.body[0]
    if not (isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant)
            and isinstance(first.value.value, str)):
        return None
    if len(function.body) == 1:  # docstring-only body: nothing left to describe
        return None
    lines = textwrap.dedent(code).splitlines()
    return "\n".join(lines[: first.lineno - 1] + lines[first.end_lineno:]).strip("\n")


def normalize_doc(doc: str) -> str:
    """Docstring text as the model should write it: dedented, trimmed, single blank lines."""
    lines = (doc or "").replace("\r\n", "\n").strip().splitlines()
    first, rest = (lines[0].strip(), textwrap.dedent("\n".join(lines[1:]))) if lines else ("", "")
    text = (first + "\n" + rest).strip()
    text = "\n".join(line.rstrip() for line in text.splitlines())
    return re.sub(r"\n{3,}", "\n\n", text)


def keep_row(row: dict) -> bool:
    language = row.get("language")
    name = row.get("func_name") or ""
    doc = row.get("func_documentation_string") or ""
    if language not in LANGUAGES or _TEST_NAME.search(name.split(".")[-1]):
        return False
    if not doc.strip() or _BOILERPLATE.search(doc):
        return False
    if sum(ch.isascii() for ch in doc) < 0.95 * len(doc):  # keep English docs
        return False
    return True


def make_example(row: dict):
    """One training pair, or None when the row is unusable."""
    if not keep_row(row):
        return None
    language = row["language"]
    code = row.get("func_code_string") or ""
    if language == "python":
        code = strip_python_docstring(code)
        if code is None:
            return None
    target = normalize_doc(row["func_documentation_string"])
    if not is_valid_docstring(target) or not MIN_CODE_CHARS <= len(code) <= MAX_CODE_CHARS:
        return None
    if target.lower()[:40] in code.lower():  # the doc leaked into the code (copied comment): useless pair
        return None
    return {"code": code, "target": target, "lang": language}
