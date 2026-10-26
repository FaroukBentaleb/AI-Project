CREATE TABLE snippet (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(120) NOT NULL,
    language         VARCHAR(20)  NOT NULL,
    code             TEXT         NOT NULL,
    docstring        TEXT,
    docstring_edited BOOLEAN      NOT NULL DEFAULT FALSE,
    truncated        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_snippet_language_created ON snippet (language, created_at DESC, id DESC);
