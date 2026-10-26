# Review AI service

The FastAPI app accepts `POST /review` with `code_snippet` and `language`,
retrieves the closest local coding standard using `all-MiniLM-L6-v2`, and
returns the Groq review as JSON. Configure `GROQ_API_KEY` in the environment;
the service reports a clear configuration error when it is missing.

The versioned prompt is `prompts/review_v1.txt`. Increment its versioned
filename when changing the prompt so deployments can be tied to the prompt
used to generate reviews.
