CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(80) UNIQUE,
    nickname VARCHAR(80) NOT NULL DEFAULT '同学',
    anonymous BOOLEAN NOT NULL DEFAULT TRUE,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS privacy_consents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    policy_version VARCHAR(30) NOT NULL,
    consented_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    withdrawn_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS checkins (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    record_date DATE NOT NULL,
    mood_score SMALLINT NOT NULL CHECK (mood_score BETWEEN 1 AND 5),
    stress_score SMALLINT NOT NULL CHECK (stress_score BETWEEN 1 AND 5),
    sleep_hours NUMERIC(4, 1),
    sleep_quality SMALLINT CHECK (sleep_quality BETWEEN 1 AND 5),
    energy_score SMALLINT CHECK (energy_score BETWEEN 1 AND 5),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, record_date)
);

CREATE TABLE IF NOT EXISTS conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    title VARCHAR(120),
    highest_risk_level VARCHAR(2) NOT NULL DEFAULT 'L0',
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id),
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    sanitized_content TEXT NOT NULL,
    risk_level VARCHAR(2) NOT NULL DEFAULT 'L0',
    model_source VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS risk_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    conversation_id BIGINT REFERENCES conversations(id),
    risk_level VARCHAR(2) NOT NULL,
    trigger_source VARCHAR(40) NOT NULL,
    reasons JSONB NOT NULL DEFAULT '[]',
    handling_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS activities (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    category VARCHAR(40) NOT NULL,
    duration_minutes INTEGER,
    content TEXT NOT NULL,
    min_risk_level VARCHAR(2) NOT NULL DEFAULT 'L0',
    max_risk_level VARCHAR(2) NOT NULL DEFAULT 'L1',
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    activity_id BIGINT REFERENCES activities(id),
    title VARCHAR(120) NOT NULL,
    planned_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'TODO',
    source VARCHAR(20) NOT NULL DEFAULT 'USER',
    helpful_score SMALLINT CHECK (helpful_score BETWEEN 1 AND 5),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id BIGSERIAL PRIMARY KEY,
    document_key VARCHAR(120) UNIQUE NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    source VARCHAR(300),
    tags JSONB NOT NULL DEFAULT '[]',
    embedding VECTOR(1536),
    review_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS campus_resources (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    contact VARCHAR(120),
    address VARCHAR(240),
    service_hours VARCHAR(240),
    emergency BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    operator_id BIGINT REFERENCES users(id),
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(40),
    target_id VARCHAR(80),
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_checkins_user_date ON checkins(user_id, record_date DESC);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_tasks_user_planned ON tasks(user_id, planned_at);
CREATE INDEX IF NOT EXISTS idx_risk_events_level_created ON risk_events(risk_level, created_at DESC);
