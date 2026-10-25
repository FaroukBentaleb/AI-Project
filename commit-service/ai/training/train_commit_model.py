# %% [markdown]
# # Commit message model: LoRA fine-tuning of Qwen2.5-0.5B-Instruct on CommitPackFT
#
# **Kaggle setup:** Settings -> Accelerator = GPU T4 (or P100), Internet = **On**, then *Run All* (about 40-60 min).
# At the end, download `commit_adapter.zip` from the *Output* tab and unzip it into `commit-service/ai/model/adapter/`.
#
# Input = a unified diff (same format commit-service builds). Target = a Conventional Commits subject.
# Metrics: BLEU and ROUGE-L on a held-out test set, for the base model (zero-shot) and the fine-tuned model.

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


for _module, _spec in [("peft", "peft>=0.13"), ("sacrebleu", "sacrebleu"), ("rouge_score", "rouge-score"),
                       ("requests", "requests")]:
    _ensure(_module, _spec)

import requests
import sacrebleu
import torch
from peft import LoraConfig, get_peft_model
from rouge_score import rouge_scorer
from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments

# %% Configuration
TEST_MODE = os.environ.get("COMMIT_TEST_MODE") == "1"  # tiny CPU smoke run, used to check this notebook offline

BASE_MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
SEED = 42
# Max usable examples per CommitPackFT language (lowercase dataset folder names).
LANG_CAP = {"python": 900, "javascript": 900, "typescript": 700, "html": 900, "css": 900, "markdown": 500,
            "java": 600, "php": 400, "ruby": 400, "go": 400, "c++": 400, "c#": 400, "shell": 300, "json": 200,
            "yaml": 300, "kotlin": 300, "swift": 300, "rust": 300}
TEST_N = 300            # held-out examples used for the metrics
MAX_LEN = 1536          # prompt + answer tokens; longer examples are dropped
EPOCHS = 1
LEARNING_RATE = 2e-4
BATCH_SIZE = 2
GRAD_ACCUM = 8          # effective batch 16
MAX_STEPS = -1
EVAL_N = 300
LORA_R = 16

if TEST_MODE:
    LANG_CAP = {"html": 30}
    TEST_N, MAX_LEN, EVAL_N = 6, 768, 4
    BATCH_SIZE, GRAD_ACCUM, MAX_STEPS = 2, 1, 2

WORK = Path("/kaggle/working") if Path("/kaggle/working").exists() else Path("runs")
OUT = WORK / "commit_model"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(SEED)
torch.manual_seed(SEED)
print("device:", torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu", "| prompt", PROMPT_VERSION)

# %% Data: stream the first rows of each CommitPackFT language file (no full download)
DATA_URL = "https://huggingface.co/datasets/bigcode/commitpackft/resolve/main/data/{lang}/data.jsonl"


def stream_rows(lang, limit):
    with requests.get(DATA_URL.format(lang=lang), stream=True, timeout=60) as response:
        response.raise_for_status()
        for i, line in enumerate(response.iter_lines()):
            if i >= limit:
                break
            if line:
                yield json.loads(line)


examples, seen = [], set()
for lang, cap in LANG_CAP.items():
    kept = 0
    for row in stream_rows(lang, int(cap * 1.4)):
        example = make_example(row)
        key = (example["target"], example["diff"][:300]) if example else None
        if example is None or key in seen:
            continue
        seen.add(key)
        examples.append(example)
        kept += 1
        if kept >= cap:
            break
    print(f"{lang:<12} {kept:>5} examples")

random.shuffle(examples)
test_examples = examples[-TEST_N:]
train_examples = examples[:-TEST_N]
print(f"\ntrain {len(train_examples)} | test {len(test_examples)}")
print("types:", dict(Counter(e["target"].split(":")[0] for e in train_examples)))
print("median diff chars:", int(statistics.median(len(e["diff"]) for e in train_examples)))
for e in train_examples[:5]:
    print("  e.g.", e["target"])

# %% Tokenize: loss only on the answer, never on the prompt
tok = AutoTokenizer.from_pretrained(BASE_MODEL)


def prompt_text(diff):
    return tok.apply_chat_template(build_messages(diff), tokenize=False, add_generation_prompt=True)


def encode(example):
    prompt_ids = tok(prompt_text(example["diff"]), add_special_tokens=False)["input_ids"]
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

# %% Evaluate: BLEU and ROUGE-L, base model (zero-shot) vs fine-tuned
model.config.use_cache = True
model.eval()
tok.padding_side = "left"


def generate(examples_, batch_size=8):
    outputs = []
    for i in range(0, len(examples_), batch_size):
        prompts = [prompt_text(e["diff"]) for e in examples_[i:i + batch_size]]
        enc = tok(prompts, return_tensors="pt", padding=True, add_special_tokens=False).to(model.device)
        with torch.inference_mode():
            generated = model.generate(**enc, max_new_tokens=40, do_sample=False, eos_token_id=tok.eos_token_id,
                                      pad_token_id=tok.pad_token_id)
        outputs += [clean_message(tok.decode(row, skip_special_tokens=True))
                    for row in generated[:, enc["input_ids"].shape[1]:]]
    return outputs


def score(predictions, references):
    rouge = rouge_scorer.RougeScorer(["rougeL"])
    return {
        "bleu": round(sacrebleu.corpus_bleu(predictions, [references]).score, 2),
        "rouge_l": round(100 * statistics.mean(rouge.score(r, p)["rougeL"].fmeasure
                                               for r, p in zip(references, predictions)), 2),
        "type_accuracy": round(100 * statistics.mean(p.split(":")[0] == r.split(":")[0]
                                                     for r, p in zip(references, predictions)), 1),
        "conventional_format": round(100 * statistics.mean(is_conventional(p) for p in predictions), 1),
    }


eval_set = test_examples[:EVAL_N]
references = [e["target"] for e in eval_set]
tuned_predictions = generate(eval_set)
with model.disable_adapter():
    base_predictions = generate(eval_set)
metrics = {"base_model_zero_shot": score(base_predictions, references),
           "fine_tuned": score(tuned_predictions, references), "test_examples": len(eval_set)}
print(json.dumps(metrics, indent=2))
for e, base, tuned in list(zip(eval_set, base_predictions, tuned_predictions))[:12]:
    print(f"\nreference : {e['target']}\nbase      : {base}\nfine-tuned: {tuned}")

# %% Save the adapter (small) + a record of how it was made
adapter_dir = OUT / "adapter"
model.save_pretrained(adapter_dir)
(adapter_dir / "training_info.json").write_text(json.dumps({
    "base_model": BASE_MODEL, "prompt_version": PROMPT_VERSION, "max_diff_chars": MAX_DIFF_CHARS,
    "train_examples": len(train_data), "languages": list(LANG_CAP), "epochs": EPOCHS, "lora_r": LORA_R,
    "metrics": metrics, "test_mode": TEST_MODE}, indent=2))
archive = shutil.make_archive(str(WORK / "commit_adapter"), "zip", adapter_dir)
print("saved", archive, f"({Path(archive).stat().st_size / 1e6:.1f} MB)")
