# Snippet docstring model

Fine-tuning pipeline for the snippet-service LLM feature: **function -> docstring**. Same approach as
`commit-service/ai`: a LoRA fine-tune of a small pre-trained Hugging Face model, trained on Kaggle, with the adapter
served by the FastAPI service in `app.py` (`POST /generate {"code","language"} -> {"docstring"}`, compose service
`snippet-model`, host port 8001). There is no fallback: the service loads the adapter before it starts answering and
stops with a clear error when `model/adapter/` is missing.

| | |
|---|---|
| Base model | `Qwen/Qwen2.5-Coder-0.5B-Instruct` (code-specialised causal LM, runs on CPU; override with `SNIPPET_BASE_MODEL`) |
| Technique | Causal language modelling (CLM) with LoRA (r=16), loss on the answer tokens only |
| Dataset | CodeSearchNet (`code-search-net/code_search_net`): Python, Java, JavaScript |
| Metrics | BLEU, ROUGE-L, exact match (full text and first sentence) for base vs fine-tuned, plus samples for human review |

The base model is a decoder (CLM), as the project sheet asks. CodeBERT is encoder-only and cannot generate text;
StarCoder-1B is a valid alternative (gated on Hugging Face, ~2x slower on CPU) but is a plain completion model, so
its prompt format would have to change.

## 1. Train on Kaggle

1. Kaggle -> **New Notebook** -> **File -> Import notebook** -> upload `training/snippet_model_kaggle.ipynb`.
2. Settings: **Accelerator = GPU T4 x2 (or P100)**, **Internet = On**.
3. **Run All** (about 45-70 min). The notebook downloads the CodeSearchNet parquet files itself (~1 GB, fast on Kaggle).
4. The last cells print the metrics table (keep it for the report) and write `snippet_adapter.zip`.
   Download it from the notebook's **Output** tab.

The zip contains the adapter, `training_info.json` (base model, prompt version, metrics) and
`human_review_samples.json` (40 code / reference / base / fine-tuned quadruples for the human review).

## 2. Install the adapter

Unzip it so that `snippet-service/ai/model/adapter/adapter_config.json` exists (git-ignored folder).

## 3. Run it

```bash
docker compose up -d --build snippet-model snippet-service
curl localhost:8001/health      # base model, device, training metrics
```

The first start downloads the base model (~1 GB) into the `snippet-model-cache` volume, then loads it (about 2.5 GB of
RAM in float32; `SNIPPET_DTYPE=bfloat16` roughly halves that on small machines). Generation runs on CPU and takes a few
seconds to a minute per function.

Without Docker: `pip install torch -r requirements.txt -r requirements-model.txt`, then `uvicorn app:app --port 8001`.

## Keeping training and serving in sync

`snippet_prompt.py` holds the system prompt, code trimming, output cleaning and per-language docstring formatting.
The notebook inlines it and the serving app will import it, so both build the model input identically. After changing
it, bump `PROMPT_VERSION`, run `python training/make_notebook.py` and retrain. The Java side must trim code to the same
`MAX_CODE_CHARS` (2500).

## Data notes

- In CodeSearchNet's Python rows `func_code_string` still contains the docstring; `strip_python_docstring` removes it
  with `ast` so the model cannot copy the answer. Java/JavaScript doc comments are stored separately.
- Kept: English docs of 10-600 chars, no `{@inheritDoc}` / auto-generated / TODO, no test functions, code of
  40-2500 chars, no duplicates. About 80 % of the raw rows survive.
- The model writes plain docstring text; `format_docstring` wraps it in `"""..."""` or `/** ... */`.
- Held-out test examples come from the dataset's *validation* split, never from the training split.

## Development

```bash
cd snippet-service/ai
python -m unittest discover -s training -t .     # prompt + data-prep tests, standard library only
python training/make_notebook.py                 # regenerate training/snippet_model_kaggle.ipynb
```

`SNIPPET_TEST_MODE=1` runs the notebook as a tiny CPU smoke test (2 training steps, 20 examples).
