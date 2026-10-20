"""Deterministic stand-in for the Hugging Face Inference endpoints used by sql-service (Story 8.1 E2E).

POST /{org}/{model}                               -> text generation: [{"generated_text": "<sql>"}]
POST /{org}/{model}/pipeline/feature-extraction   -> embedding: flat float array (EMBEDDING_DIM, default 384)
GET  /health                                      -> 200

The reply is picked from the user's question (the text inside [QUESTION]...[/QUESTION]), so the E2E script can
drive every UJ-1 path: a normal question gets read-only SQL on the team's `orders` table, a question containing
"delete" gets a DELETE (-> 422 SQL_REJECTED), and one containing "outage" gets an HF 503 (-> 503 LLM_UNAVAILABLE).
No token is checked and nothing is forwarded anywhere. Standard library only.
"""
import hashlib
import json
import math
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DIM = int(os.environ.get("EMBEDDING_DIM", "384"))
PORT = int(os.environ.get("PORT", "8099"))

ORDERS_SQL = (
    "SELECT r.name AS region, COUNT(*) AS orders_count\n"
    "FROM orders o JOIN regions r ON r.id = o.region_id\n"
    "WHERE o.created_at >= CURRENT_DATE - INTERVAL '7 days'\n"
    "GROUP BY r.name\nORDER BY orders_count DESC"
)


def question(prompt: str) -> str:
    m = re.search(r"\[QUESTION\](.*?)\[/QUESTION\]", prompt or "", re.S)
    return (m.group(1) if m else prompt or "").lower()


def embed(text: str) -> list:
    """Hashed bag of words, L2-normalised: texts sharing words get a high cosine score."""
    v = [0.0] * DIM
    for w in re.findall(r"[a-z0-9_]+", (text or "").lower()):
        h = int.from_bytes(hashlib.sha256(w.encode()).digest()[:4], "big")
        v[h % DIM] += 1.0
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

    def do_GET(self):
        self._send(200, {"status": "ok"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            body = {}
        inputs = body.get("inputs", "")
        if self.path.rstrip("/").endswith("/pipeline/feature-extraction"):
            return self._send(200, embed(inputs if isinstance(inputs, str) else " ".join(map(str, inputs))))
        q = question(inputs if isinstance(inputs, str) else "")
        if "outage" in q:
            return self._send(503, {"error": "Model is currently loading", "estimated_time": 20.0})
        if "delete" in q:
            return self._send(200, [{"generated_text": "DELETE FROM orders WHERE created_at < CURRENT_DATE - 30"}])
        return self._send(200, [{"generated_text": ORDERS_SQL}])

    def log_message(self, fmt, *args):
        print("llm-stub:", fmt % args, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
