CREATE TABLE review (
    id           BIGSERIAL PRIMARY KEY,
    code_snippet TEXT        NOT NULL,
    ai_response  TEXT        NOT NULL,
    status       VARCHAR(32) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_review_created_at ON review (created_at DESC, id DESC);
