CREATE TABLE saved_query (
    id                 BIGSERIAL PRIMARY KEY,
    title              VARCHAR(200)  NOT NULL,
    description        VARCHAR(2000) NOT NULL,
    sql_content        TEXT          NOT NULL CHECK (char_length(sql_content) <= 20000),
    tags               TEXT[]        NOT NULL DEFAULT '{}',
    dialect            VARCHAR(20)   NOT NULL DEFAULT 'postgresql',
    notes              TEXT,
    author             VARCHAR(100)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    is_llm_generated   BOOLEAN       NOT NULL DEFAULT FALSE,
    original_nl_prompt TEXT,
    version            INT           NOT NULL DEFAULT 0
);
