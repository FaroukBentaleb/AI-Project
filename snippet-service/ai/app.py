"""Docstring model service used by snippet-service.

    POST /generate   {"code": "<function>", "language": "python|java|javascript"}   ->   {"docstring": "<text>"}
    GET  /health     {"status": "UP", "base_model": ..., "metrics": ...}

Serves the fine-tuned LoRA adapter in SNIPPET_MODEL_DIR (default ./model, containing adapter/ or adapter_config.json,
produced by the Kaggle notebook in training/) on top of its base model. The model is loaded before the server starts
answering: without a usable adapter the process stops with a clear error instead of faking answers.
"""
import json
import logging
import os
import threading
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from snippet_prompt import PROMPT_VERSION, build_messages, clean_docstring, is_valid_docstring

log = logging.getLogger("snippet-model")
MODEL_DIR = Path(os.getenv("SNIPPET_MODEL_DIR", Path(__file__).parent / "model"))
MAX_NEW_TOKENS = int(os.getenv("SNIPPET_MAX_NEW_TOKENS", "160"))


class GenerateRequest(BaseModel):
    code: str = Field(min_length=1)
    language: Literal["python", "java", "javascript"]


class GenerateResponse(BaseModel):
    docstring: str


class AdapterModel:
    """Base model with the fine-tuned LoRA adapter merged in (loaded once, generation is serialized)."""

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
        base = os.getenv("SNIPPET_BASE_MODEL", config["base_model_name_or_path"])
        self.torch = torch
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.tokenizer = AutoTokenizer.from_pretrained(base, padding_side="left")
        # float32 on CPU (about 2.5 GB); SNIPPET_DTYPE=bfloat16 halves the memory on small machines.
        dtype = getattr(torch, os.getenv("SNIPPET_DTYPE") or ("float16" if self.device == "cuda" else "float32"))
        model = AutoModelForCausalLM.from_pretrained(base, torch_dtype=dtype, low_cpu_mem_usage=True)
        self.model = PeftModel.from_pretrained(model, str(adapter_dir)).merge_and_unload().to(self.device).eval()
        self.lock = threading.Lock()
        self.base = base

    def generate(self, code: str, language: str) -> str:
        prompt = self.tokenizer.apply_chat_template(build_messages(code, language), tokenize=False,
                                                    add_generation_prompt=True)
        inputs = self.tokenizer(prompt, return_tensors="pt", add_special_tokens=False).to(self.device)
        with self.lock, self.torch.inference_mode():
            output = self.model.generate(
                **inputs, max_new_tokens=MAX_NEW_TOKENS, do_sample=False, eos_token_id=self.tokenizer.eos_token_id,
                pad_token_id=self.tokenizer.pad_token_id)
        return clean_docstring(self.tokenizer.decode(output[0, inputs["input_ids"].shape[1]:],
                                                     skip_special_tokens=True))


def find_adapter(model_dir: Path):
    for candidate in (model_dir / "adapter", model_dir):
        if (candidate / "adapter_config.json").exists():
            return candidate
    return None


model: AdapterModel | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global model
    adapter = find_adapter(MODEL_DIR)
    if adapter is None:
        raise RuntimeError(f"no adapter found in {MODEL_DIR}: unzip snippet_adapter.zip into {MODEL_DIR / 'adapter'}")
    model = AdapterModel(adapter)
    log.warning("loaded %s + adapter %s on %s", model.base, adapter, model.device)
    yield


app = FastAPI(title="snippet-model", lifespan=lifespan)


@app.post("/generate", response_model=GenerateResponse)
def generate(request: GenerateRequest) -> GenerateResponse:
    try:
        docstring = model.generate(request.code, request.language)
    except Exception:
        log.exception("generation failed")
        raise HTTPException(status_code=503, detail="The model could not generate a docstring")
    if not is_valid_docstring(docstring):
        log.warning("model returned an unusable docstring (%d chars)", len(docstring))
        raise HTTPException(status_code=502, detail="The model returned an unusable docstring")
    return GenerateResponse(docstring=docstring)


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "prompt_version": PROMPT_VERSION, "base_model": model.base, "device": model.device,
            "metrics": model.info.get("metrics")}
