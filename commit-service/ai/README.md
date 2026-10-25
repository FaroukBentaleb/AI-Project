# Commit message model

`commit-service` sends the diff of an upload to this service (`POST /generate {"diff"} -> {"message"}`).
The service serves a LoRA fine-tune of **Qwen2.5-0.5B-Instruct** trained on **CommitPackFT** to write
Conventional Commits subjects (`feat: add about page`). Until an adapter is installed it answers with a simple
rule-based fallback, so the app always works. `GET /health` shows `"mode": "model"` or `"stub"`.

## 1. Train on Kaggle

1. Open Kaggle, **New Notebook**, **File -> Import notebook**, upload `training/commit_model_kaggle.ipynb`.
2. Settings: **Accelerator = GPU T4 x2 (or P100)**, **Internet = On**.
3. **Run All** (about 40-60 min). The last cells print BLEU / ROUGE-L for the base model and the fine-tuned model
   on a held-out test set (keep those numbers for the report) and write `commit_adapter.zip`.
4. Download `commit_adapter.zip` from the notebook's **Output** tab.

## 2. Install the adapter

Unzip it so that this file exists: `commit-service/ai/model/adapter/adapter_config.json`
(next to `adapter_model.safetensors` and `training_info.json`). The `model/` folder is git-ignored.

## 3. Run it

With Docker (CPU inference, the base model is downloaded once into the `commit-model-cache` volume):

```bash
# .env
COMMIT_MODEL_REAL=1
docker compose up -d --build commit-model commit-service
curl localhost:8000/health        # -> "mode": "model"
```

Or directly on this machine (uses the GPU if PyTorch can see it):

```bash
cd commit-service/ai
pip install -r requirements.txt torch -r requirements-model.txt
uvicorn app:app --port 8000
```

## Keeping training and serving in sync

`commit_prompt.py` holds the system prompt, the diff trimming and the output cleaning. The notebook inlines it and
`app.py` imports it, so both build the model input identically. After changing it, bump `PROMPT_VERSION`, run
`python training/make_notebook.py` to rebuild the notebook, and retrain. The Java side trims diffs to the same
`MAX_DIFF_CHARS` (`commit.model.max-diff-chars`, 3500).

## Development

```bash
cd commit-service/ai
python -m unittest discover -s training -t .     # prompt + data-prep tests, standard library only
python training/make_notebook.py                 # regenerate training/commit_model_kaggle.ipynb
```

`COMMIT_TEST_MODE=1` runs the notebook as a tiny CPU smoke test (2 training steps on 30 examples).
