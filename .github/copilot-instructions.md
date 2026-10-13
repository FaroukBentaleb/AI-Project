# DevAssist Hub Copilot Instructions

DevAssist Hub is an ESPRIT full-stack project.
It has five independent CRUD modules.
Each module has one Spring Boot microservice and one Angular feature module.
Eureka and Spring Cloud Gateway provide discovery and routing.
PostgreSQL stores relational data and Qdrant stores commit embeddings.

## Ownership and conventions

- One teammate owns one service folder and its Angular feature module.
- Keep the service package layout: `controller`, `service`, `repository`,
  `dto`, `entity`, and `exception`.
- Use clear Java and Angular names that match the existing service/module name.
- Use Conventional Commits.
- Use branches named `feature/<service>-<topic>`.
- See [`docs/PROJECT_CONTEXT.md`](../docs/PROJECT_CONTEXT.md) for the source of truth.

## LLM rules

- Use a dedicated Hugging Face client class.
- Enforce timeout and retry behavior.
- Report quota-exceeded and missing-key errors clearly.
- Validate and parse model output before returning it.
- Never send raw personal data to the model.
- Store prompts under a `prompts/` resource folder, not in controllers.
- Version and document prompts.

## Hard rules

- Never commit secrets or `.env`.
- Never edit another member's service.
- Keep diffs minimal.
- Ask before changing shared files such as Compose, gateway routes, or init scripts.
