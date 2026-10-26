# %% [markdown]
# # Bug triage model: LoRA fine-tuning of Qwen2.5-0.5B-Instruct on Mozilla Bugzilla defects
#
# **Kaggle setup:** Settings -> Accelerator = **GPU T4 x2** (or P100), Internet = **On**, then *Run All*
# (about 30-45 min: ~5 min download, ~20 min training, ~10 min evaluation).
# At the end, download `triage_adapter.zip` from the *Output* tab and unzip it into `bug-service/ai/model/adapter/`.
#
# Input = a bug report rendered with bug-service's own prompt (prompts/v1). Target = the triage JSON
# `{"severity", "category", "title"}`, with labels taken from Bugzilla (S1-S4 severity, keywords/component,
# the triagers' summary line). Metrics on a held-out test set, base model (zero-shot) vs fine-tuned:
# valid-JSON rate, severity and category accuracy / macro-F1, title ROUGE-L.

# %% Setup
import os

# One GPU only: Trainer would otherwise wrap the model in DataParallel on a T4 x2 machine.
os.environ.setdefault("CUDA_VISIBLE_DEVICES", "0")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import importlib
import json
import random
import shutil
import statistics
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path


def _ensure(module, spec):
    try:
        importlib.import_module(module)
    except ImportError:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", spec])


# Kaggle ships an old torchao that recent peft versions refuse to import next to; LoRA does not need it.
subprocess.run([sys.executable, "-m", "pip", "uninstall", "-y", "torchao"], capture_output=True)

for _module, _spec in [("peft", "peft>=0.13"), ("rouge_score", "rouge-score"), ("requests", "requests")]:
    _ensure(_module, _spec)

import requests
import torch
from peft import LoraConfig, get_peft_model
from rouge_score import rouge_scorer
from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments

# %% Configuration
TEST_MODE = os.environ.get("TRIAGE_TEST_MODE") == "1"  # tiny CPU smoke run, used to check this notebook offline

BASE_MODEL = "Qwen/Qwen2.5-0.5B-Instruct"   # "Qwen/Qwen2.5-1.5B-Instruct" is better but ~3x slower to train and serve
SEED = 42
# Fixed Bugzilla defects per severity (S1 is rare: ~600 exist). Candidates are fetched with some headroom
# because noisy bugs are dropped.
PER_SEVERITY = {"S1": 550, "S2": 1000, "S3": 1000, "S4": 1000}
CREATED_SINCE = "2019-01-01"   # modern reports, written for the S1-S4 scale
TEST_N = 300                   # held-out, stratified by severity
MAX_LEN = 2048                 # prompt + answer tokens; longer examples are dropped
EPOCHS = 2
LEARNING_RATE = 2e-4
BATCH_SIZE = 2
GRAD_ACCUM = 8                 # effective batch 16
MAX_STEPS = -1
EVAL_N = 300
LORA_R = 16

if TEST_MODE:
    PER_SEVERITY = {"S1": 4, "S2": 4, "S3": 4, "S4": 4}
    TEST_N, EVAL_N, MAX_LEN = 4, 3, 1536
    BATCH_SIZE, GRAD_ACCUM, MAX_STEPS, EPOCHS = 1, 1, 2, 1

WORK = Path("/kaggle/working") if Path("/kaggle/working").exists() else Path("runs")
OUT = WORK / "triage_model"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(SEED)
torch.manual_seed(SEED)
print("device:", torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu", "| prompt", PROMPT_VERSION)

# %% Data: fixed Mozilla defects from the public Bugzilla REST API (no account needed), cached in WORK
API = "https://bugzilla.mozilla.org/rest"
CACHE = WORK / "bugzilla_raw.jsonl"
http = requests.Session()
http.headers["User-Agent"] = "devassist-hub-triage-training (ESPRIT student project)"


def get(path, params):
    for attempt in range(5):
        response = http.get(f"{API}/{path}", params=params, timeout=120)
        if response.status_code in (429, 500, 502, 503, 504):
            time.sleep(5 * (attempt + 1))
            continue
        response.raise_for_status()
        return response.json()
    response.raise_for_status()


def search(severity, wanted):
    bugs, offset = [], 0
    while len(bugs) < wanted:
        page = get("bug", {"type": "defect", "resolution": "FIXED", "severity": severity,
                           "creation_time": CREATED_SINCE, "order": "bug_id DESC", "limit": 500, "offset": offset,
                           "include_fields": "id,summary,severity,component,product,keywords"})["bugs"]
        if not page:
            break
        bugs += page
        offset += len(page)
    return bugs[:wanted]


def first_comments(ids):
    """Bug id -> text of comment 0 (the reporter's description), 50 bugs per request."""
    texts = {}
    for i in range(0, len(ids), 50):
        chunk = ids[i:i + 50]
        data = get(f"bug/{chunk[0]}/comment", [("ids", n) for n in chunk[1:]] + [("include_fields", "text,count")])
        for bug_id, entry in data["bugs"].items():
            texts[int(bug_id)] = next((c["text"] for c in entry["comments"] if c.get("count") == 0), "")
        time.sleep(0.2)
    return texts


if CACHE.exists() and not TEST_MODE:
    raw = [json.loads(line) for line in CACHE.read_text(encoding="utf-8").splitlines()]
    print(f"loaded {len(raw)} cached bugs from {CACHE}")
else:
    raw = []
    for severity, cap in PER_SEVERITY.items():
        bugs = search(severity, int(cap * 1.5))
        comments = first_comments([b["id"] for b in bugs])
        raw += [dict(b, description=comments.get(b["id"], "")) for b in bugs]
        print(f"{severity}: fetched {len(bugs)} bugs")
    CACHE.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in raw), encoding="utf-8")

by_severity = {}
for row in raw:
    example = make_example(row, row["description"])
    if example:
        by_severity.setdefault(row["severity"], []).append(example)
examples = []
for severity, cap in PER_SEVERITY.items():
    kept = by_severity.get(severity, [])[:cap]
    examples += kept
    print(f"{severity} -> {SEVERITY_MAP[severity]:<8} {len(kept):>5} usable examples")

# Stratified split: the same severity mix in train and test.
random.shuffle(examples)
test_examples, train_examples = [], []
for severity in SEVERITIES:
    group = [e for e in examples if e["severity"] == severity]
    n = max(1, round(TEST_N * len(group) / max(1, len(examples)))) if group else 0
    test_examples += group[:n]
    train_examples += group[n:]
random.shuffle(test_examples)
random.shuffle(train_examples)
print(f"\ntrain {len(train_examples)} | test {len(test_examples)}")
print("severity:", dict(Counter(e["severity"] for e in train_examples)))
print("category:", dict(Counter(e["category"] for e in train_examples)))
for e in train_examples[:5]:
    print("  e.g.", e["target"])

# %% Tokenize: loss only on the answer, never on the prompt
tok = AutoTokenizer.from_pretrained(BASE_MODEL)


def prompt_text(example):
    messages = build_messages(TRIAGE_SYSTEM_PROMPT, TRIAGE_USER_TEMPLATE, example["description"])
    return tok.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)


def encode(example):
    prompt_ids = tok(prompt_text(example), add_special_tokens=False)["input_ids"]
    answer_ids = tok(example["target"] + tok.eos_token, add_special_tokens=False)["input_ids"]
    if len(prompt_ids) + len(answer_ids) > MAX_LEN:
        return None
    return {"input_ids": prompt_ids + answer_ids, "labels": [-100] * len(prompt_ids) + answer_ids}


train_data = [item for item in (encode(e) for e in train_examples) if item]
print(f"{len(train_data)}/{len(train_examples)} examples fit in {MAX_LEN} tokens; "
      f"mean length {statistics.mean(len(d['input_ids']) for d in train_data):.0f}")


def collate(batch):
    width = max(len(item["input_ids"]) for item in batch)
    pad = tok.pad_token_id
    return {
        "input_ids": torch.tensor([i["input_ids"] + [pad] * (width - len(i["input_ids"])) for i in batch]),
        "attention_mask": torch.tensor([[1] * len(i["input_ids"]) + [0] * (width - len(i["input_ids"])) for i in batch]),
        "labels": torch.tensor([i["labels"] + [-100] * (width - len(i["labels"])) for i in batch]),
    }


# %% Model + LoRA
use_cuda = torch.cuda.is_available()
model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float16 if use_cuda else torch.float32)
model.config.use_cache = False
# The system prompt makes every example ~1000 tokens: checkpointing keeps activations small (T4 and CPU alike).
model.gradient_checkpointing_enable()
model.enable_input_require_grads()
model = get_peft_model(model, LoraConfig(
    r=LORA_R, lora_alpha=2 * LORA_R, lora_dropout=0.05, task_type="CAUSAL_LM",
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]))
for param in model.parameters():  # fp16 autocast needs fp32 trainable weights
    if param.requires_grad:
        param.data = param.data.float()
model.print_trainable_parameters()

# %% Train
steps_per_epoch = max(1, -(-len(train_data) // (BATCH_SIZE * GRAD_ACCUM)))
total_steps = MAX_STEPS if MAX_STEPS > 0 else steps_per_epoch * EPOCHS
args = TrainingArguments(
    output_dir=str(OUT / "checkpoints"), per_device_train_batch_size=BATCH_SIZE,
    gradient_accumulation_steps=GRAD_ACCUM, num_train_epochs=EPOCHS, max_steps=MAX_STEPS,
    learning_rate=LEARNING_RATE, lr_scheduler_type="cosine", warmup_steps=max(1, int(0.03 * total_steps)),
    logging_steps=10, save_strategy="no", fp16=use_cuda, report_to="none", remove_unused_columns=False, seed=SEED,
    dataloader_num_workers=0, use_cpu=not use_cuda)
started = time.time()
Trainer(model=model, args=args, train_dataset=train_data, data_collator=collate).train()
print(f"trained in {(time.time() - started) / 60:.1f} min")
import transformers, peft
print("versions: torch", torch.__version__, "| transformers", transformers.__version__, "| peft", peft.__version__)

# %% Save the adapter now, before the (long) evaluation: a Colab/Kaggle reset later on cannot lose the training
adapter_dir = OUT / "adapter"


def save_adapter(metrics=None):
    """Writes adapter + tokenizer + training_info.json and zips them; called again after evaluation."""
    model.save_pretrained(adapter_dir)
    tok.save_pretrained(adapter_dir)
    (adapter_dir / "training_info.json").write_text(json.dumps({
        "base_model": BASE_MODEL, "prompt_version": PROMPT_VERSION, "dataset": "bugzilla.mozilla.org fixed defects "
        "since " + CREATED_SINCE, "per_severity": PER_SEVERITY, "train_examples": len(train_data), "epochs": EPOCHS,
        "lora_r": LORA_R, "max_description": MAX_DESCRIPTION, "metrics": metrics, "test_mode": TEST_MODE}, indent=2))
    archive = shutil.make_archive(str(WORK / "triage_adapter"), "zip", adapter_dir)
    print("saved", archive, f"({Path(archive).stat().st_size / 1e6:.1f} MB)")
    return archive


archive = save_adapter()

# %% Evaluate: base model (zero-shot) vs fine-tuned, on the same held-out reports
model.config.use_cache = True
model.eval()
tok.padding_side = "left"


def generate(examples_, batch_size=8):
    outputs = []
    for i in range(0, len(examples_), batch_size):
        prompts = [prompt_text(e) for e in examples_[i:i + batch_size]]
        enc = tok(prompts, return_tensors="pt", padding=True, add_special_tokens=False).to(model.device)
        with torch.inference_mode():
            # Room for the base model's verbose answers (with a rationale), so its JSON is not cut and judged unfairly.
            generated = model.generate(**enc, max_new_tokens=192, do_sample=False, eos_token_id=tok.eos_token_id,
                                       pad_token_id=tok.pad_token_id)
        outputs += [tok.decode(row, skip_special_tokens=True) for row in generated[:, enc["input_ids"].shape[1]:]]
    return outputs


def macro_f1(pairs, labels):
    scores = []
    for label in labels:
        tp = sum(1 for r, p in pairs if r == label and p == label)
        fp = sum(1 for r, p in pairs if r != label and p == label)
        fn = sum(1 for r, p in pairs if r == label and p != label)
        if tp + fp + fn:
            scores.append(2 * tp / (2 * tp + fp + fn))
    return round(100 * statistics.mean(scores), 1) if scores else 0.0


def score(raw_outputs, references):
    parsed = [parse_output(text) for text in raw_outputs]
    rouge = rouge_scorer.RougeScorer(["rougeL"])
    # An unparseable answer counts as wrong on every field (that is what bug-service would see).
    severity = [(r["severity"], (p or {}).get("severity")) for r, p in zip(references, parsed)]
    category_ = [(r["category"], (p or {}).get("category")) for r, p in zip(references, parsed)]
    return {
        "valid_json": round(100 * statistics.mean(p is not None for p in parsed), 1),
        "severity_accuracy": round(100 * statistics.mean(r == p for r, p in severity), 1),
        "severity_macro_f1": macro_f1(severity, SEVERITIES),
        "category_accuracy": round(100 * statistics.mean(r == p for r, p in category_), 1),
        "category_macro_f1": macro_f1(category_, CATEGORIES),
        "title_rouge_l": round(100 * statistics.mean(
            rouge.score(r["title"], p["title"])["rougeL"].fmeasure if p else 0.0
            for r, p in zip(references, parsed)), 1),
    }


eval_set = test_examples[:EVAL_N]
tuned_outputs = generate(eval_set)
with model.disable_adapter():
    base_outputs = generate(eval_set)
metrics = {"base_model_zero_shot": score(base_outputs, eval_set), "fine_tuned": score(tuned_outputs, eval_set),
           "test_examples": len(eval_set),
           "majority_class_severity_accuracy": round(100 * Counter(e["severity"] for e in eval_set).most_common(1)[0][1]
                                                     / max(1, len(eval_set)), 1)}
print(json.dumps(metrics, indent=2))

confusion = Counter((e["severity"], (parse_output(t) or {}).get("severity", "INVALID")) for e, t in zip(eval_set, tuned_outputs))
print("\nseverity confusion (reference -> fine-tuned prediction):")
for ref in SEVERITIES:
    print(f"  {ref:<8}", {pred: n for (r, pred), n in sorted(confusion.items()) if r == ref})
for e, base, tuned in list(zip(eval_set, base_outputs, tuned_outputs))[:8]:
    print(f"\nreference : {e['target']}\nbase      : {base.strip()[:200]}\nfine-tuned: {tuned.strip()[:200]}")

# %% Save again with the metrics, then download (Colab: browser download; Kaggle: Output tab or the link below)
archive = save_adapter(metrics)
try:
    from google.colab import files
    files.download(archive)
except ImportError:
    try:
        from IPython.display import FileLink, display
        display(FileLink(Path(archive).relative_to(WORK)))
    except ImportError:  # plain script (offline smoke test)
        pass
