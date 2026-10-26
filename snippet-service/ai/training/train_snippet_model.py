# %% [markdown]
# # Snippet docstring model: LoRA fine-tuning of Qwen2.5-Coder-0.5B-Instruct on CodeSearchNet
#
# **Kaggle setup:** Settings -> Accelerator = GPU T4 (or P100), Internet = **On**, then *Run All* (about 45-70 min).
# At the end, download `snippet_adapter.zip` from the *Output* tab and unzip it into `snippet-service/ai/model/adapter/`.
#
# Input = one function (Python / Java / JavaScript). Target = its docstring text, written by a human in the
# CodeSearchNet repositories. Causal language modelling: the loss is computed on the answer tokens only.
# Metrics: BLEU, ROUGE-L and exact match on a held-out set taken from CodeSearchNet's *validation* split
# (never used for training), for the base model (zero-shot) and the fine-tuned model, plus samples for human review.

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

for _module, _spec in [("peft", "peft>=0.13"), ("sacrebleu", "sacrebleu"), ("rouge_score", "rouge-score"),
                       ("requests", "requests"), ("pyarrow", "pyarrow")]:
    _ensure(_module, _spec)

import pyarrow.parquet as pq
import requests
import sacrebleu
import torch
from peft import LoraConfig, get_peft_model
from rouge_score import rouge_scorer
from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments

# %% Configuration
TEST_MODE = os.environ.get("SNIPPET_TEST_MODE") == "1"  # tiny CPU smoke run, used to check this notebook offline

BASE_MODEL = os.environ.get("SNIPPET_BASE_MODEL", "Qwen/Qwen2.5-Coder-0.5B-Instruct")
SEED = 42
# Max usable training examples per language.
LANG_CAP = {"python": 2500, "java": 1500, "javascript": 1000}
TEST_PER_LANG = 100     # held-out examples per language used for the metrics
MAX_LEN = 1024          # prompt + answer tokens; longer examples are dropped
EPOCHS = 1
LEARNING_RATE = 2e-4
BATCH_SIZE = 2
GRAD_ACCUM = 8          # effective batch 16
MAX_STEPS = -1
EVAL_N = 300
LORA_R = 16
MAX_NEW_TOKENS = 160
TRAIN_SPLIT = "train"

if TEST_MODE:
    LANG_CAP = {"python": 20}
    TEST_PER_LANG, MAX_LEN, EVAL_N, MAX_NEW_TOKENS = 3, 768, 3, 24
    BATCH_SIZE, GRAD_ACCUM, MAX_STEPS = 2, 1, 2
    TRAIN_SPLIT = "validation"  # the train file is ~500 MB; the smoke run only needs a few rows

WORK = Path("/kaggle/working") if Path("/kaggle/working").exists() else Path("runs")
OUT = WORK / "snippet_model"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(SEED)
torch.manual_seed(SEED)
print("device:", torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu",
      "| prompt", PROMPT_VERSION, "| base", BASE_MODEL)

# %% Data: CodeSearchNet parquet files from the Hugging Face dataset API
PARQUET_API = "https://huggingface.co/api/datasets/code-search-net/code_search_net/parquet/{lang}/{split}"
COLUMNS = ["language", "func_name", "func_code_string", "func_documentation_string"]


def load_rows(lang, split):
    """All rows of the first parquet file of a CodeSearchNet language split (train files are ~100-500 MB)."""
    url = requests.get(PARQUET_API.format(lang=lang, split=split), timeout=60).json()[0]
    path = WORK / f"csn-{lang}-{split}.parquet"
    with requests.get(url, stream=True, timeout=120) as response:
        response.raise_for_status()
        with open(path, "wb") as handle:
            for chunk in response.iter_content(chunk_size=1 << 20):
                handle.write(chunk)
    table = pq.read_table(path, columns=COLUMNS)
    path.unlink()
    return table.to_pylist()


def collect(lang, split, cap):
    rows = load_rows(lang, split)
    random.shuffle(rows)  # files are ordered by repository: sample across them
    examples, seen = [], set()
    for row in rows:
        example = make_example(row)
        key = (example["target"], example["code"][:200]) if example else None
        if example is None or key in seen:
            continue
        seen.add(key)
        examples.append(example)
        if len(examples) >= cap:
            break
    print(f"{lang:<12} {split:<10} {len(examples):>5} examples (from {len(rows)} rows)")
    return examples


train_examples, test_examples = [], []
for lang, cap in LANG_CAP.items():
    train_examples += collect(lang, TRAIN_SPLIT, cap)
    # Held-out set from the validation split (disjoint from train); in test mode both come from validation.
    test_examples += collect(lang, "validation", TEST_PER_LANG + (cap if TEST_MODE else 0))[-TEST_PER_LANG:]

random.shuffle(train_examples)
random.shuffle(test_examples)
train_codes = {e["code"] for e in train_examples}
test_examples = [e for e in test_examples if e["code"] not in train_codes]
print(f"\ntrain {len(train_examples)} | test {len(test_examples)}")
print("languages:", dict(Counter(e["lang"] for e in train_examples)))
print("median code chars:", int(statistics.median(len(e["code"]) for e in train_examples)),
      "| median doc chars:", int(statistics.median(len(e["target"]) for e in train_examples)))
for e in train_examples[:3]:
    print("  e.g.", e["lang"], "->", e["target"].splitlines()[0])

# %% Tokenize: loss only on the answer, never on the prompt
tok = AutoTokenizer.from_pretrained(BASE_MODEL)


def prompt_text(example):
    return tok.apply_chat_template(build_messages(example["code"], example["lang"]), tokenize=False,
                                   add_generation_prompt=True)


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
if use_cuda:
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
    learning_rate=LEARNING_RATE, lr_scheduler_type="cosine", warmup_steps=max(1, int(0.03 * total_steps)), logging_steps=10,
    save_strategy="no", fp16=use_cuda, report_to="none", remove_unused_columns=False, seed=SEED,
    dataloader_num_workers=0, use_cpu=not use_cuda)
started = time.time()
Trainer(model=model, args=args, train_dataset=train_data, data_collator=collate).train()
print(f"trained in {(time.time() - started) / 60:.1f} min")
import transformers, peft
print("versions: torch", torch.__version__, "| transformers", transformers.__version__, "| peft", peft.__version__)

# %% Evaluate: BLEU, ROUGE-L and exact match, base model (zero-shot) vs fine-tuned
model.config.use_cache = True
model.eval()
tok.padding_side = "left"


def generate(examples_, batch_size=8):
    outputs = []
    for i in range(0, len(examples_), batch_size):
        prompts = [prompt_text(e) for e in examples_[i:i + batch_size]]
        enc = tok(prompts, return_tensors="pt", padding=True, add_special_tokens=False).to(model.device)
        with torch.inference_mode():
            generated = model.generate(**enc, max_new_tokens=MAX_NEW_TOKENS, do_sample=False,
                                      eos_token_id=tok.eos_token_id, pad_token_id=tok.pad_token_id)
        outputs += [clean_docstring(tok.decode(row, skip_special_tokens=True))
                    for row in generated[:, enc["input_ids"].shape[1]:]]
    return outputs


def _norm(text):
    return " ".join(text.lower().split())


def score(predictions, references):
    rouge = rouge_scorer.RougeScorer(["rougeL"])
    return {
        "bleu": round(sacrebleu.corpus_bleu(predictions, [references]).score, 2),
        "rouge_l": round(100 * statistics.mean(rouge.score(r, p)["rougeL"].fmeasure
                                               for r, p in zip(references, predictions)), 2),
        "exact_match": round(100 * statistics.mean(_norm(p) == _norm(r) for r, p in zip(references, predictions)), 1),
        "summary_exact_match": round(100 * statistics.mean(
            _norm(p.split("\n")[0]) == _norm(r.split("\n")[0]) for r, p in zip(references, predictions)), 1),
        "valid_docstring": round(100 * statistics.mean(is_valid_docstring(p) for p in predictions), 1),
    }


eval_set = test_examples[:EVAL_N]
references = [e["target"] for e in eval_set]
tuned_predictions = generate(eval_set)
with model.disable_adapter():
    base_predictions = generate(eval_set)
metrics = {"base_model_zero_shot": score(base_predictions, references),
           "fine_tuned": score(tuned_predictions, references), "test_examples": len(eval_set)}
print(json.dumps(metrics, indent=2))
for e, base, tuned in list(zip(eval_set, base_predictions, tuned_predictions))[:8]:
    print(f"\n[{e['lang']}] {e['code'][:160]!r}\nreference : {e['target'][:200]!r}\n"
          f"base      : {base[:200]!r}\nfine-tuned: {tuned[:200]!r}")

# %% Save the adapter (small) + a record of how it was made + samples for the human review
adapter_dir = OUT / "adapter"
model.save_pretrained(adapter_dir)
(adapter_dir / "training_info.json").write_text(json.dumps({
    "base_model": BASE_MODEL, "prompt_version": PROMPT_VERSION, "max_code_chars": MAX_CODE_CHARS,
    "train_examples": len(train_data), "languages": LANG_CAP, "epochs": EPOCHS, "lora_r": LORA_R,
    "metrics": metrics, "test_mode": TEST_MODE}, indent=2))
(adapter_dir / "human_review_samples.json").write_text(json.dumps([
    {"lang": e["lang"], "code": e["code"], "reference": e["target"], "base": b, "fine_tuned": t}
    for e, b, t in list(zip(eval_set, base_predictions, tuned_predictions))[:40]], indent=2))
archive = shutil.make_archive(str(WORK / "snippet_adapter"), "zip", adapter_dir)
print("saved", archive, f"({Path(archive).stat().st_size / 1e6:.1f} MB)")
