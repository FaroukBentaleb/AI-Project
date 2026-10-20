"""Deterministic stand-in for the Hugging Face Inference endpoints used by sql-service (Story 8.1 E2E).

POST /{org}/{model}                               -> text generation: [{"generated_text": "<sql>"}]
POST /{org}/{model}/pipeline/feature-extraction   -> embedding: flat float array (EMBEDDING_DIM, default 384)
GET  /health                                      -> 200

The reply is picked from the user's question (the text inside [QUESTION]...[/QUESTION]), so the E2E script can
drive every UJ-1 path: a normal question gets read-only SQL on the team's `orders` table, a question containing
"delete" gets a DELETE (-> 422 SQL_REJECTED), and one containing "outage" gets an HF 503 (-> 503 LLM_UNAVAILABLE).
Story 8.3 (UJ-3 RAG lift): a normal question with <example> blocks in the prompt gets the first example's SQL
back (so its table names show up in the answer); without examples the stub guesses a conventional table name from
the question, like an ungrounded model would. No token is checked and nothing is forwarded anywhere. Standard library only.
"""
import hashlib
import json
import math
import os
import random
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIM = int(os.environ.get("EMBEDDING_DIM", "384"))
PORT = int(os.environ.get("PORT", "8099"))
# Story 8.2: added latency on text-generation calls only (burst test, Plan B rehearsal). Embeddings stay fast.
LATENCY_MS = int(os.environ.get("STUB_LATENCY_MS", "0") or 0)
JITTER_MS = int(os.environ.get("STUB_JITTER_MS", "0") or 0)

ORDERS_SQL = (
    "SELECT r.name AS region, COUNT(*) AS orders_count\n"
    "FROM orders o JOIN regions r ON r.id = o.region_id\n"
    "WHERE o.created_at >= CURRENT_DATE - INTERVAL '7 days'\n"
    "GROUP BY r.name\nORDER BY orders_count DESC"
)


def question(prompt: str) -> str:
    m = re.search(r"\[QUESTION\](.*?)\[/QUESTION\]", prompt or "", re.S)
    return (m.group(1) if m else prompt or "").lower()


EXAMPLE = re.compile(r'<example id="[^"]*">\s*(.*?)\s*</example>', re.S)
GUESS_STOP = {"show", "list", "get", "find", "all", "per", "by", "last", "this", "week", "month", "year", "how",
              "many", "what", "which", "total", "number", "count", "each", "every", "the", "a", "of", "for", "in"}


def first_example_sql(prompt: str):
    m = EXAMPLE.search(prompt or "")
    if not m:
        return None
    body = [ln for ln in m.group(1).split(chr(10)) if not ln.lstrip().startswith("-- intent:")]
    sql = chr(10).join(body).strip()
    return sql or None


def guessed_sql(q: str) -> str:
    words = [w for w in re.findall(r"[a-z_]+", q) if w not in GUESS_STOP and len(w) > 2]
    table = (words[0] if words else "records") + "_data"
    return f"SELECT * FROM {table} LIMIT 100"


STOP = {"select", "from", "where", "group", "order", "by", "join", "on", "as", "and", "or", "the", "a", "of",
        "to", "in", "is", "for", "with", "count", "sum", "desc", "asc", "limit", "interval", "current_date"}


def embed(text: str) -> list:
    """Hashed set of words (SQL keywords and stopwords dropped), L2-normalised. The first line - the title of an
    indexed query, or the whole prompt - weighs 4x, so a prompt close to a query's title scores above 0.5."""
    v = [0.0] * DIM
    lines = (text or "").lower().split(chr(10))
    for i, line in enumerate(lines):
        for w in set(re.findall(r"[a-z0-9_]+", line)) - STOP:
            h = int.from_bytes(hashlib.sha256(w.encode()).digest()[:4], "big")
            v[h % DIM] += 4.0 if i == 0 else 1.0
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v] if any(v) else [1.0 / math.sqrt(DIM)] * DIM


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, headers=None):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> bytes:
        """Reads a Content-Length or chunked body (the JDK HTTP client sends chunked requests)."""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    return data
                data += self.rfile.read(size)
                self.rfile.readline()
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def do_GET(self):
        self._send(200, {"status": "ok"})

    def do_POST(self):
        try:
            body = json.loads(self._body() or b"{}")
        except ValueError:
            body = {}
        inputs = body.get("inputs", "")
        if self.path.rstrip("/").endswith("/pipeline/feature-extraction"):
            return self._send(200, embed(inputs if isinstance(inputs, str) else " ".join(map(str, inputs))))
        if LATENCY_MS or JITTER_MS:
            time.sleep(max(0, LATENCY_MS + random.randint(-JITTER_MS, JITTER_MS)) / 1000.0)
        q = question(inputs if isinstance(inputs, str) else "")
        if "outage" in q:
            return self._send(503, {"error": "Model is currently loading", "estimated_time": 20.0})
        if "delete" in q:
            return self._send(200, [{"generated_text": "DELETE FROM orders WHERE created_at < CURRENT_DATE - 30"}])
        prompt = inputs if isinstance(inputs, str) else ""
        grounded = first_example_sql(prompt)
        if grounded:
            return self._send(200, [{"generated_text": grounded}])
        if "<example" in prompt or "No schema is provided" in prompt:
            return self._send(200, [{"generated_text": guessed_sql(q)}])
        return self._send(200, [{"generated_text": ORDERS_SQL}])

    def log_message(self, fmt, *args):
        print("llm-stub:", fmt % args, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
