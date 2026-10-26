"""Prompt, code trimming and output cleaning/formatting shared by training (Kaggle notebook) and serving.

Training and serving must build the model input identically, so both import (or inline) this file. Changing
anything here changes PROMPT_VERSION: retrain the adapter and bump the version.

The model writes the docstring *text* only (plain prose, optional Args/Returns lines). `format_docstring` then wraps
it in the comment syntax of the language, so the model never has to learn quoting/indentation rules.
"""
import re
import textwrap

PROMPT_VERSION = "v1"
LANGUAGES = ("python", "java", "javascript")
# Code budget sent to the model; the Java side must trim to the same size (`snippet.model.max-code-chars`).
MAX_CODE_CHARS = 2500
CUT_MARKER = "\n... [code truncated]"
MAX_DOC_CHARS = 600

SYSTEM_PROMPT = (
    "You are a documentation generator. Given one function, reply with its docstring text only: a short summary "
    "sentence, then optional lines describing the parameters and the return value. Plain text, no code, no quotes, "
    f"no comment markers. At most {MAX_DOC_CHARS} characters."
)


def trim_code(code: str, limit: int = MAX_CODE_CHARS) -> str:
    """Cut to `limit` chars on a line boundary, marking the cut."""
    if len(code) <= limit:
        return code
    head = code[: max(0, limit - len(CUT_MARKER))]
    newline = head.rfind("\n")
    if newline > 0:
        head = head[:newline]
    return head + CUT_MARKER


def build_messages(code: str, language: str) -> list:
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": f"Language: {language}\n\n{trim_code(code)}"},
    ]


def clean_docstring(text: str) -> str:
    """Model reply -> plain docstring text: no fences, quotes or comment markers, tidy whitespace."""
    text = (text or "").strip()
    text = re.sub(r"^```\w*\n?|\n?```$", "", text).strip()
    for opener, closer in (('"""', '"""'), ("'''", "'''"), ("/**", "*/"), ("/*", "*/")):
        if text.startswith(opener):
            text = text[len(opener):]
            if text.rstrip().endswith(closer):
                text = text.rstrip()[: -len(closer)]
            break
    lines = [re.sub(r"^\s*(\*|//|#)\s?", "", line).rstrip() for line in text.splitlines()]
    text = textwrap.dedent("\n".join(lines)).strip()
    return re.sub(r"\n{3,}", "\n\n", text)[:MAX_DOC_CHARS]


def format_docstring(text: str, language: str, indent: str = "") -> str:
    """Docstring text -> comment block in the language's syntax, ready to paste above/inside the function."""
    text = clean_docstring(text)
    lines = text.splitlines() or [""]
    if language == "python":
        if len(lines) == 1:
            return f'{indent}"""{lines[0]}"""'
        body = "\n".join(f"{indent}{line}".rstrip() for line in lines)
        return f'{indent}"""\n{body}\n{indent}"""'
    if language in ("java", "javascript"):
        body = "\n".join(f"{indent} * {line}".rstrip() for line in lines)
        return f"{indent}/**\n{body}\n{indent} */"
    raise ValueError(f"unsupported language: {language}")


def is_valid_docstring(text: str) -> bool:
    return 10 <= len(text) <= MAX_DOC_CHARS and "```" not in text and len(text.split()) >= 3
