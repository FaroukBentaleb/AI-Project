"""Commit-message model service used by commit-service.

    POST /generate   {"diff": "<unified diff>"}   ->   {"message": "<conventional commit subject>"}
    GET  /health     {"status": "UP", "mode": "model" | "stub", ...}

With a trained LoRA adapter in COMMIT_MODEL_DIR (default ./model, containing adapter/ or adapter_config.json,
produced by the Kaggle notebook in training/) it serves the fine-tuned Qwen model. Without one, or if the model
cannot be loaded or answers with nothing usable, it falls back to a simple rule-based message so the app keeps
working.
"""
import json
import logging
import os
import re
import threading
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel, Field

from commit_prompt import PROMPT_VERSION, build_messages, clean_message

log = logging.getLogger("commit-model")
MODEL_DIR = Path(os.getenv("COMMIT_MODEL_DIR", Path(__file__).parent / "model"))
MAX_NEW_TOKENS = int(os.getenv("COMMIT_MAX_NEW_TOKENS", "48"))


class GenerateRequest(BaseModel):
    diff: str = Field(min_length=1)


class GenerateResponse(BaseModel):
    message: str


class AdapterModel:
    """Qwen base model with the fine-tuned LoRA adapter merged in (loaded once, generation is serialized)."""

    def __init__(self, adapter_dir: Path):
        import torch
        from peft import PeftModel
        from transformers import AutoModelForCausalLM, AutoTokenizer

        config = json.loads((adapter_dir / "adapter_config.json").read_text())
        info_file = adapter_dir / "training_info.json"
        self.info = json.loads(info_file.read_text()) if info_file.exists() else {}
        if self.info.get("prompt_version", PROMPT_VERSION) != PROMPT_VERSION:
            log.warning("adapter was trained with prompt %s but the server uses %s",
                        self.info.get("prompt_version"), PROMPT_VERSION)
        base = os.getenv("COMMIT_BASE_MODEL", config["base_model_name_or_path"])
        self.torch = torch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.tokenizer = AutoTokenizer.from_pretrained(base, padding_side="left")
        model = AutoModelForCausalLM.from_pretrained(
            base, torch_dtype=torch.float16 if self.device == "cuda" else torch.float32)
        self.model = PeftModel.from_pretrained(model, str(adapter_dir)).merge_and_unload().to(self.device).eval()
        self.lock = threading.Lock()
        self.base = base

    def generate(self, diff: str) -> str:
        prompt = self.tokenizer.apply_chat_template(build_messages(diff), tokenize=False, add_generation_prompt=True)
        inputs = self.tokenizer(prompt, return_tensors="pt", add_special_tokens=False).to(self.device)
        with self.lock, self.torch.inference_mode():
            output = self.model.generate(
                **inputs, max_new_tokens=MAX_NEW_TOKENS, do_sample=False, eos_token_id=self.tokenizer.eos_token_id,
                pad_token_id=self.tokenizer.pad_token_id)
        return clean_message(self.tokenizer.decode(output[0, inputs["input_ids"].shape[1]:], skip_special_tokens=True))


def find_adapter(model_dir: Path):
    for candidate in (model_dir / "adapter", model_dir):
        if (candidate / "adapter_config.json").exists():
            return candidate
    return None


_FILE_HEADER = re.compile(r"^diff --git a/(.+?) b/", re.MULTILINE)


def heuristic_message(diff: str) -> str:
    """Rule-based fallback: counts added/deleted files. Used only when no trained model answers."""
    files = _FILE_HEADER.findall(diff)
    added = len(re.findall(r"^--- /dev/null$", diff, re.MULTILINE))
    deleted = len(re.findall(r"^\+\+\+ /dev/null$", diff, re.MULTILINE))
    if not files:
        return "chore: update project files"
    if added == len(files):
        return f"feat: add {files[0]}" if len(files) == 1 else f"feat: add {len(files)} files"
    if deleted == len(files):
        return f"chore: remove {files[0]}" if len(files) == 1 else f"chore: remove {len(files)} files"
    return f"fix: update {files[0]}" if len(files) == 1 else f"chore: update {len(files)} files"


model: AdapterModel | None = None
load_error: str | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global model, load_error
    adapter = find_adapter(MODEL_DIR)
    if adapter is None:
        log.warning("no adapter in %s: serving the rule-based fallback", MODEL_DIR)
    else:
        try:
            model = AdapterModel(adapter)
            log.info("loaded %s + adapter %s on %s", model.base, adapter, model.device)
        except Exception as error:  # missing torch/transformers, bad files, no network for the base model...
            load_error = f"{type(error).__name__}: {error}"
            log.exception("could not load the model: serving the rule-based fallback")
    yield


app = FastAPI(title="commit-model", lifespan=lifespan)


@app.post("/generate", response_model=GenerateResponse)
def generate(request: GenerateRequest) -> GenerateResponse:
    if model is not None:
        try:
            message = model.generate(request.diff)
            if message:
                return GenerateResponse(message=message)
            log.warning("model returned an empty message: using the fallback")
        except Exception:
            log.exception("generation failed: using the fallback")
    return GenerateResponse(message=heuristic_message(request.diff))


@app.get("/health")
def health() -> dict:
    status = {"status": "UP", "mode": "model" if model is not None else "stub", "prompt_version": PROMPT_VERSION}
    if model is not None:
        status.update(base_model=model.base, device=model.device, metrics=model.info.get("metrics"))
    if load_error:
        status["load_error"] = load_error
    return status
