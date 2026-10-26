"""Bug triage model service used by bug-service, speaking the OpenAI chat-completions protocol.

    POST /v1/chat/completions   {"model", "messages": [...], "max_tokens"}  ->  {"choices": [{"message": {...}}]}
    GET  /health                {"status": "UP", "mode": "model" | "stub", ...}

bug-service already calls an OpenAI-compatible endpoint, so pointing it here is configuration only:
HF_BASE_URL (compose: BUG_LLM_BASE_URL) = http://localhost:8001/v1, with any non-blank API key.

With a trained LoRA adapter in TRIAGE_MODEL_DIR (default ./model, containing adapter/ or adapter_config.json, made
by the Kaggle notebook in training/) it serves the fine-tuned Qwen model on the messages exactly as bug-service
sends them (the model was trained on that prompt). Without one, or if the model fails, it answers with a
rule-based triage so the app keeps working.
"""
import json
import logging
import os
import re
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel, Field

log = logging.getLogger("triage-model")
MODEL_DIR = Path(os.getenv("TRIAGE_MODEL_DIR", Path(__file__).parent / "model"))
MAX_NEW_TOKENS = int(os.getenv("TRIAGE_MAX_NEW_TOKENS", "96"))


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    model: str | None = None
    messages: list[ChatMessage] = Field(min_length=1)
    max_tokens: int | None = None


class AdapterModel:
    """Qwen base model with the fine-tuned LoRA adapter merged in (loaded once, generation is serialized)."""

    def __init__(self, adapter_dir: Path):
        import torch
        from peft import PeftModel
        from transformers import AutoModelForCausalLM, AutoTokenizer

        config = json.loads((adapter_dir / "adapter_config.json").read_text())
        info_file = adapter_dir / "training_info.json"
        self.info = json.loads(info_file.read_text()) if info_file.exists() else {}
        base = os.getenv("TRIAGE_BASE_MODEL", config["base_model_name_or_path"])
        self.torch = torch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.tokenizer = AutoTokenizer.from_pretrained(base)
        model = AutoModelForCausalLM.from_pretrained(
            base, torch_dtype=torch.float16 if self.device == "cuda" else torch.float32)
        self.model = PeftModel.from_pretrained(model, str(adapter_dir)).merge_and_unload().to(self.device).eval()
        self.lock = threading.Lock()
        self.base = base

    def generate(self, messages: list[dict], max_new_tokens: int) -> str:
        prompt = self.tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        inputs = self.tokenizer(prompt, return_tensors="pt", add_special_tokens=False).to(self.device)
        with self.lock, self.torch.inference_mode():
            output = self.model.generate(
                **inputs, max_new_tokens=max_new_tokens, do_sample=False, eos_token_id=self.tokenizer.eos_token_id,
                pad_token_id=self.tokenizer.pad_token_id)
        return self.tokenizer.decode(output[0, inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()


def find_adapter(model_dir: Path):
    for candidate in (model_dir / "adapter", model_dir):
        if (candidate / "adapter_config.json").exists():
            return candidate
    return None


_DESCRIPTION = re.compile(r"Description:\s*\n(.*?)(?:\n\s*\nSteps to reproduce:|</report>|$)", re.S)


def heuristic_triage(user_text: str) -> str:
    """Rule-based fallback on the report text. Used only when no trained model answers."""
    match = _DESCRIPTION.search(user_text)
    description = (match.group(1) if match else user_text).strip()
    text = user_text.lower()

    def has(*words):
        return any(re.search(rf"\b{w}", text) for w in words)

    if has("secur", "xss", "inject", "csrf", "password", "token", "permission", "unauthori"):
        category = "SECURITY"
    elif has("crash", "freez", "hang", "exception", "stack trace", "segfault", "500"):
        category = "CRASH"
    elif has("data loss", "lost", "corrupt", "duplicat", "deleted"):
        category = "DATA"
    elif has("slow", "timeout", "latency", "memory", "cpu", "lag"):
        category = "PERFORMANCE"
    elif has("api", "webhook", "third.party", "integration", "sync", "network"):
        category = "INTEGRATION"
    elif has("button", "layout", "css", "color", "colour", "align", "font", "display", "typo", "icon", "screen"):
        category = "UI"
    else:
        category = "FUNCTIONAL"

    if category in ("SECURITY", "DATA") or has("all users", "everyone", "production down", "cannot log", "blank page"):
        severity = "CRITICAL"
    elif category == "CRASH" or has("cannot", "can't", "unable", "broken", "fails", "error", "blocks"):
        severity = "HIGH"
    elif has("typo", "cosmetic", "misaligned", "spelling", "minor"):
        severity = "LOW"
    else:
        severity = "MEDIUM"

    first = re.split(r"(?<=[.!?])\s|\n", description.replace("(not provided)", "").strip(), maxsplit=1)[0]
    title = re.sub(r"\s+", " ", first).strip(" .!")[:90].strip() or "Unexpected behavior reported"
    if len(title) < 5:
        title = "Unexpected behavior reported"
    return json.dumps({"severity": severity, "category": category, "title": title[0].upper() + title[1:],
                       "rationale": "Rule-based fallback: no trained triage model is loaded."})


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


app = FastAPI(title="triage-model", lifespan=lifespan)


@app.post("/v1/chat/completions")
def chat_completions(request: ChatRequest) -> dict:
    messages = [m.model_dump() for m in request.messages]
    content, served_by = None, "stub"
    if model is not None:
        try:
            content = model.generate(messages, min(request.max_tokens or MAX_NEW_TOKENS, 256))
            served_by = model.base
        except Exception:
            log.exception("generation failed: using the fallback")
    if not content:
        last_user = next((m["content"] for m in reversed(messages) if m["role"] == "user"), "")
        # A repair turn carries no report: triage the original report instead.
        report = next((m["content"] for m in messages if m["role"] == "user" and "<report>" in m["content"]), last_user)
        content = heuristic_triage(report)
    return {"id": f"chatcmpl-{uuid.uuid4().hex[:12]}", "object": "chat.completion", "created": int(time.time()),
            "model": served_by, "choices": [{"index": 0, "finish_reason": "stop",
                                              "message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}


@app.get("/health")
def health() -> dict:
    status = {"status": "UP", "mode": "model" if model is not None else "stub"}
    if model is not None:
        status.update(base_model=model.base, device=model.device, prompt_version=model.info.get("prompt_version"),
                      metrics=model.info.get("metrics"))
    if load_error:
        status["load_error"] = load_error
    return status
