"""Builds the Kaggle notebook (and optionally a flat script) from train_triage_model.py.

    python training/make_notebook.py                 # writes training/triage_model_kaggle.ipynb
    python training/make_notebook.py --script out.py # flat script, used to smoke-test the notebook offline

The first cell inlines bug-service's prompt files (src/main/resources/prompts/<version>/) and training/triage_data.py,
so the notebook is self-contained and the model is trained on exactly the prompt TriageService sends.
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent
SERVICE_DIR = HERE.parent.parent
PROMPT_VERSION = "v1"


def shared_code() -> str:
    prompts = SERVICE_DIR / "src" / "main" / "resources" / "prompts" / PROMPT_VERSION
    system = (prompts / "triage-system.txt").read_text(encoding="utf-8").strip()
    user = (prompts / "triage-user.txt").read_text(encoding="utf-8").strip()
    data = (HERE / "triage_data.py").read_text(encoding="utf-8").strip()
    return "\n".join([
        f"# ---- bug-service prompts/{PROMPT_VERSION} (do not edit here: change the .txt files and rebuild) ----",
        f"PROMPT_VERSION = {PROMPT_VERSION!r}",
        f"TRIAGE_SYSTEM_PROMPT = {system!r}",
        f"TRIAGE_USER_TEMPLATE = {user!r}",
        "",
        "# ---- triage_data.py ----",
        data,
    ])


def split_cells(script: str):
    cells, current, kind = [], [], "code"
    for line in script.splitlines():
        if line.startswith("# %%"):
            if current:
                cells.append((kind, current))
            kind = "markdown" if "[markdown]" in line else "code"
            title = line[4:].replace("[markdown]", "").strip()
            current = [f"# {title}"] if title and kind == "code" else []
        else:
            current.append(line)
    cells.append((kind, current))
    return cells


def notebook(cells) -> dict:
    out = []
    for kind, lines in cells:
        if kind == "markdown":
            lines = [re.sub(r"^# ?", "", line) for line in lines]
        source = "\n".join(lines).strip("\n")
        item = {"cell_type": kind, "metadata": {}, "source": source.splitlines(keepends=True)}
        if kind == "code":
            item.update(execution_count=None, outputs=[])
        out.append(item)
    return {"cells": out, "nbformat": 4, "nbformat_minor": 5, "metadata": {
        "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
        "language_info": {"name": "python"}}}


def main():
    script = (HERE / "train_triage_model.py").read_text(encoding="utf-8")
    script_cells = split_cells(script)
    # Markdown title first, then the shared helpers, then the rest.
    cells = script_cells[:1] + [("code", ["# Shared prompt + data helpers", *shared_code().splitlines()])] \
        + script_cells[1:]
    if "--script" in sys.argv:
        target = Path(sys.argv[sys.argv.index("--script") + 1])
        target.write_text("\n\n".join("\n".join(lines) for kind, lines in cells if kind == "code"), encoding="utf-8")
    else:
        target = HERE / "triage_model_kaggle.ipynb"
        target.write_text(json.dumps(notebook(cells), indent=1), encoding="utf-8")
    print("wrote", target)


if __name__ == "__main__":
    main()
