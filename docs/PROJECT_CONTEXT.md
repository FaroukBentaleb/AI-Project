# Project Context

## Course brief

**IA for Software Engineering** is an ESPRIT mini-project. The goal is a web
application with a front end, back end, and an AI module, developed by teams of
5–6.

Deliverables:

- Versioned code
- Ideally deployed live demo
- Presentation of prompts and/or models used
- Technical report covering architecture, frameworks, APIs, models, and prompts

Calendar:

- Week 1: subject choice
- Week 3: progress check with the teacher
- Week 7: final delivery of code and report, followed by a 15-minute live demo,
  Q&A, and every member present

Grading may be weighted per member using Git commits and task split.

## Our scope

Each member owns one module and one microservice. Every module must provide a
full CRUD and one LLM feature.

| Module | What the LLM does | Input → Output |
|---|---|---|
| Commits | Generate a commit message with RAG over past commits | Diff → commit message |
| Code snippets | Generate documentation metadata | Snippet → docstring + tags |
| Bug reports | Classify and summarize a report | Report → severity, category, suggested title (JSON) |
| SQL queries | Generate safe read-only SQL | Natural language → SQL |
| Code reviews | Explain code quality and security findings | Code → smells and vulnerabilities with explanations |

## LLM rules

Every module must:

- Call the Hugging Face model through a dedicated client class.
- Apply a strict timeout and retry policy.
- Return clear errors for quota exhaustion and a missing key.
- Validate and parse model output before returning it; never trust raw output.
- Never send raw personal data to the model.
- Store prompts in a `prompts/` resource folder, not in controllers.
- Version and document every prompt so it can be shown in the report.

## Service template

Each service follows this package layout:

```text
controller/
service/
repository/
dto/
entity/
exception/
```

Each service also provides:

- `/actuator/health`
- OpenAPI documentation
- Eureka registration
- Its own PostgreSQL database
- A global exception handler with a consistent JSON error format

The commit service additionally owns the Qdrant integration for the
`commit_embeddings` collection, using 384-dimensional cosine vectors.
The SQL service owns its own `sql_embeddings` collection in the same Qdrant (cosine, size from
`HF_EMBEDDING_DIMENSION`, default 384) and never touches other collections.

## Definition of done

A module is done when:

- CRUD works.
- The LLM feature works.
- Model output is validated.
- Errors are handled clearly.
- Service-layer tests pass.
- The Angular feature module is connected through the gateway.
- The prompt is documented and versioned.
