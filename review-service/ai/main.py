import os
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from groq import APIConnectionError, APIStatusError, Groq, RateLimitError
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer
from sklearn.metrics.pairwise import cosine_similarity

load_dotenv()

app = FastAPI(title="DevAssist Hub Review AI")
embedding_model = SentenceTransformer("all-MiniLM-L6-v2")
standards = [
    "Validate external input before using it and return clear errors for invalid data.",
    "Keep functions focused and avoid unnecessary mutation of shared state.",
    "Do not hardcode credentials or expose sensitive information in logs or responses.",
    "Handle expected failures explicitly and use resource-safe cleanup for I/O.",
]
standard_embeddings = embedding_model.encode(standards)
groq_api_key = os.getenv("GROQ_API_KEY", "").strip()
groq_client = Groq(api_key=groq_api_key, timeout=60, max_retries=2) if groq_api_key else None
with (Path(__file__).parent / "prompts" / "review_v1.txt").open(encoding="utf-8") as prompt_file:
    review_prompt_template = prompt_file.read()


class ReviewRequest(BaseModel):
    code_snippet: str = Field(min_length=1, max_length=100000)
    language: str = Field(min_length=1, max_length=50)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/review")
def review_code(request: ReviewRequest):
    if groq_client is None:
        raise HTTPException(
            status_code=503,
            detail="GROQ_API_KEY is not configured for the review AI service",
        )

    incoming_embedding = embedding_model.encode([request.code_snippet])
    similarities = cosine_similarity(incoming_embedding, standard_embeddings)[0]
    retrieved_snippet = standards[int(similarities.argmax())]
    prompt = review_prompt_template.format(
        retrieved_snippet=retrieved_snippet,
        code_snippet=request.code_snippet,
        language=request.language,
    )

    try:
        completion = groq_client.chat.completions.create(
            model="qwen/qwen3.8-27b",
            messages=[{"role": "user", "content": prompt}],
            temperature=0.2,
            max_tokens=1024,
        )
    except RateLimitError as exception:
        raise HTTPException(status_code=429, detail="Groq quota or rate limit exceeded") from exception
    except APIConnectionError as exception:
        raise HTTPException(status_code=502, detail="Groq service is unreachable") from exception
    except APIStatusError as exception:
        raise HTTPException(status_code=502, detail="Groq could not complete the code review") from exception

    response = completion.choices[0].message.content if completion.choices else None
    if not response or not response.strip():
        raise HTTPException(status_code=502, detail="Groq returned an empty code review")

    return {"response": response.strip(), "relevant_snippet": retrieved_snippet}
