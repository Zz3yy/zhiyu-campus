CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE garden_knowledge_documents (
    id TEXT PRIMARY KEY,
    content_hash VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    source TEXT NOT NULL,
    boundary TEXT NOT NULL,
    version TEXT NOT NULL,
    review_status TEXT NOT NULL CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewer TEXT NOT NULL DEFAULT '',
    reviewed_at DATE,
    embedding_model TEXT NOT NULL,
    embedding vector(256) NOT NULL,
    CHECK (review_status <> 'APPROVED' OR (reviewer <> '' AND reviewed_at IS NOT NULL))
);
-- The small corpus uses exact cosine search; no approximate index is needed.
