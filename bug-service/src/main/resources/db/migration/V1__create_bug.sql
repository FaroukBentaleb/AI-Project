CREATE TABLE bug (
    id                 BIGSERIAL PRIMARY KEY,
    title              VARCHAR(200) NOT NULL,
    description        TEXT         NOT NULL,
    steps_to_reproduce TEXT,
    expected_result    TEXT,
    actual_result      TEXT,
    stack_trace        TEXT,
    environment        VARCHAR(200),
    app_version        VARCHAR(50),
    severity           VARCHAR(20)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    category           VARCHAR(20)  NOT NULL CHECK (category IN ('UI', 'FUNCTIONAL', 'PERFORMANCE', 'SECURITY',
                                                                 'CRASH', 'DATA', 'INTEGRATION', 'OTHER')),
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    reporter           VARCHAR(100),
    assignee           VARCHAR(100),
    tags               TEXT[]       NOT NULL DEFAULT '{}',
    -- Last AI triage suggestion, kept apart from severity/category/title, which the user decides.
    ai_severity        VARCHAR(20),
    ai_category        VARCHAR(20),
    ai_title           VARCHAR(200),
    ai_rationale       TEXT,
    ai_model           VARCHAR(200),
    ai_prompt_version  VARCHAR(20),
    ai_triaged_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    resolved_at        TIMESTAMPTZ
);
CREATE INDEX ix_bug_status ON bug (status);
CREATE INDEX ix_bug_created ON bug (created_at DESC, id DESC);
