CREATE TABLE project (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_project_name_lower ON project (lower(name));

CREATE TABLE commit_upload (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL,
    snapshot       TEXT        NOT NULL,
    diff           TEXT        NOT NULL,
    message        TEXT        NOT NULL,
    message_edited BOOLEAN     NOT NULL DEFAULT FALSE,
    initial        BOOLEAN     NOT NULL DEFAULT FALSE,
    truncated      BOOLEAN     NOT NULL DEFAULT FALSE,
    file_count     INT         NOT NULL,
    files_changed  INT         NOT NULL
);
CREATE INDEX ix_commit_upload_project_created ON commit_upload (project_id, created_at DESC, id DESC);
