"""Builds the Kaggle notebook (and optionally a flat script) from train_snippet_model.py.

    python training/make_notebook.py                 # writes training/snippet_model_kaggle.ipynb
    python training/make_notebook.py --script out.py # flat script, used to smoke-test the notebook offline

The first cell inlines snippet_prompt.py and training/snippet_data.py so the notebook is self-contained (nothing to
upload to Kaggle besides the notebook itself).
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent
AI_DIR = HERE.parent


def shared_code() -> str:
    parts = []
    for path in (AI_DIR / "snippet_prompt.py", HERE / "snippet_data.py"):
        text = path.read_text(encoding="utf-8")
        text = re.sub(r"^from snippet_prompt import .*\n", "", text, flags=re.M)  # same namespace once inlined
        parts.append(f"# ---- {path.name} ----\n{text.strip()}\n")
    return "\n".join(parts)


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
    script = (HERE / "train_snippet_model.py").read_text(encoding="utf-8")
    cells = [("code", ["# Shared prompt + data helpers (inlined from snippet_prompt.py and snippet_data.py)",
                       *shared_code().splitlines()])] + split_cells(script)
    if "--script" in sys.argv:
        target = Path(sys.argv[sys.argv.index("--script") + 1])
        target.write_text("\n\n".join("\n".join(lines) for kind, lines in cells if kind == "code"), encoding="utf-8")
    else:
        target = HERE / "snippet_model_kaggle.ipynb"
        target.write_text(json.dumps(notebook(cells), indent=1), encoding="utf-8")
    print("wrote", target)


if __name__ == "__main__":
    main()
