# Bug triage model

Your own model for bug-service's AI triage: a LoRA fine-tune of **Qwen2.5-0.5B-Instruct**, trained on Kaggle to turn
a bug report into `{"severity", "category", "title"}`. `app.py` serves it with the OpenAI chat-completions
protocol, the one bug-service's `HfChatClient` already speaks, so switching from Hugging Face/Groq to this model is
configuration only. Until an adapter is installed, the server answers with a rule-based triage, so the app works
with no API key or credits. `GET /health` shows `"mode": "model"` or `"stub"`.

## Training data

The labels come from Mozilla's Bugzilla (public REST API, no account needed). The notebook downloads them itself.

| Label | Where it comes from |
|---|---|
| severity | Bugzilla's S1–S4 field: S1→CRITICAL, S2→HIGH, S3→MEDIUM, S4→LOW |
| category | keywords first (`sec-*`→SECURITY, `crash`→CRASH, `dataloss`→DATA, `perf`→PERFORMANCE, `access`→UI), then the component (Networking/Sync/WebExtensions→INTEGRATION, Layout/CSS/Toolbars…→UI), else FUNCTIONAL |
| title | the bug's summary line, written by Mozilla triagers |
| input | the reporter's first comment, cleaned (User-Agent boilerplate removed) and redacted with the same placeholders as `PiiRedactor` |

It uses about 3,500 fixed defects created since 2019, balanced by severity (S1 is rare, so there are ~550).
Bot-filed noise (intermittent test failures, crash-signature bugs, meta bugs) is dropped. The rules are in
`training/triage_data.py`, which is unit-tested.

The model input is **bug-service's own prompt** (`src/main/resources/prompts/v1/`), inlined into the notebook. At
training time the model sees exactly what `TriageService` sends at runtime.

## 1. Train on Kaggle (about 40 min)

1. Go to <https://www.kaggle.com> and sign in. Your phone number must be verified to use GPUs and the internet:
   **Settings → Phone verification**.
2. **Create → New Notebook**, then **File → Import Notebook**, and upload `training/triage_model_kaggle.ipynb`.
3. In the right-hand panel (**Session options**):
   - **Accelerator → GPU T4 x2** (P100 also works)
   - **Internet → On** (needed for Bugzilla and the Qwen download)
4. **Run All**. What you should see:
   - *Data*: about 5 min, with one line per severity (`S1 -> CRITICAL 5xx usable examples`, …)
   - *Train*: about 20 min, with the loss printed every 10 steps. It should drop from ~2 to well under 1.
   - *Evaluate*: about 10 min. It prints a JSON with the base model's (zero-shot) and the fine-tuned model's
     scores, plus a severity confusion table and side-by-side examples. **Screenshot this for the report.**
   - *Save*: it writes `triage_adapter.zip` (~35 MB).
5. Open the **Output** tab on the right (or `/kaggle/working`) and download `triage_adapter.zip`.

Tip: **Save Version → Save & Run All (Commit)** runs it in the background, so you can close the tab. Download the
zip from the version's **Output** section when it finishes.

## 2. Install the adapter

Unzip it so that this file exists: `bug-service/ai/model/adapter/adapter_config.json` (next to
`adapter_model.safetensors` and `training_info.json`). `model/` is git-ignored: share the zip, not the files.

## 3. Run the model server (port 8001)

Locally, without Docker (Python 3.10+): double-click `run.bat`, or run it from a terminal in `bug-service/ai`.
The first run creates `.venv` (git-ignored) and installs FastAPI, CPU PyTorch, transformers and peft. After that it
just starts uvicorn on `http://localhost:8001`.

```bash
cd bug-service/ai
.\run.bat
curl localhost:8001/health        # "mode": "model" with an adapter in model/adapter/, else "stub"
```

The server reads the adapter at startup. After unzipping a new one, stop it (Ctrl+C) and run it again. The first
start in model mode downloads the Qwen base model (~1 GB) into the Hugging Face cache (`%USERPROFILE%\.cache\huggingface`).

With Docker instead: `docker build --build-arg WITH_MODEL=1 -t bug-triage-model .` then
`docker run -d -p 8001:8001 bug-triage-model`. Without `WITH_MODEL=1` the image only serves the fallback.

## 4. Point bug-service at it

The API key only has to be non-blank, because the server ignores it.

- **IntelliJ / local run**: add to `bug-service/.env.local` (no comment lines in that file):
  ```
  HF_BASE_URL=http://localhost:8001/v1
  HF_MODEL=bug-triage-qwen0.5b
  HF_API_TOKEN=local
  ```
- **bug-service in Docker compose** (only if you run it there): in `.env` set `BUG_LLM_BASE_URL=http://host.docker.internal:8001/v1`, `BUG_LLM_API_KEY=local`
  and `BUG_LLM_MODEL=bug-triage-qwen0.5b`.

Restart bug-service. `GET /api/bugs/triage/status` shows the new provider, and "Analyze with AI" now uses your
model. A 0.5B model answers in a few seconds on CPU, within bug-service's 15 s `HF_TIMEOUT`.

## For the report

`model/adapter/training_info.json` keeps the base model, dataset, hyper-parameters, prompt version and the metrics
(base vs fine-tuned). `GET /health` returns the metrics too. Worth stating: Bugzilla severity is a judgement call,
so a model that agrees with the triagers 50–60% of the time on four classes is doing well. The notebook prints
the majority-class baseline to compare against.

## Development

```bash
cd bug-service/ai
python -m unittest discover -s training -t .     # data-prep tests, standard library only
python training/make_notebook.py                 # rebuild training/triage_model_kaggle.ipynb
```

Rebuild the notebook after changing `triage_data.py`, `train_triage_model.py` or the prompt files. If the prompt
changes (new `prompts/vN`), set `PROMPT_VERSION` in `make_notebook.py` and retrain: the model only knows the
prompt it was trained on. `TRIAGE_TEST_MODE=1` runs the notebook as a tiny CPU smoke test (16 bugs, 2 training
steps).
