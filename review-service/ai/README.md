# Review AI service

The FastAPI app accepts `POST /review` with `code_snippet` and `language`,
retrieves the closest local coding standard using `all-MiniLM-L6-v2`, and
returns the Groq review, matched standard, and cosine similarity as JSON.
Configure `GROQ_API_KEY` in the environment; the service reports a clear
configuration error when it is missing.

The Docker image installs CPU-only PyTorch to avoid bundling CUDA and NVIDIA
runtime packages. The MiniLM embedding model is cached in the Compose volume
`review-ai-cache`, so it does not need to be downloaded again after a container
rebuild. Groq performs the language-model inference remotely.

The versioned prompt is `prompts/review_v1.txt`. Increment its versioned
filename when changing the prompt so deployments can be tied to the prompt
used to generate reviews.

The Spring API exposes `POST` and `GET /api/reviews`, `GET` and `PUT
/api/reviews/{id}`, and `DELETE /api/reviews/{id}`. Updating a review reruns the
AI analysis on the submitted code. The Angular reviews page offers demo
snippets, context match visualization, searchable history, and saved review
management.
