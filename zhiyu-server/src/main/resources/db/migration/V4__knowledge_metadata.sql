ALTER TABLE garden_knowledge_documents
    ADD COLUMN category text NOT NULL DEFAULT 'activity',
    ADD COLUMN time_minutes integer NOT NULL DEFAULT 3 CHECK (time_minutes BETWEEN 1 AND 60),
    ADD COLUMN target_emotions text[] NOT NULL DEFAULT '{}',
    ADD COLUMN scenes text[] NOT NULL DEFAULT '{}',
    ADD COLUMN cognitive_load_rank integer NOT NULL DEFAULT 1 CHECK (cognitive_load_rank BETWEEN 0 AND 2);

CREATE INDEX garden_knowledge_time_review ON garden_knowledge_documents (review_status, time_minutes);
CREATE INDEX garden_knowledge_scenes ON garden_knowledge_documents USING gin (scenes);
CREATE INDEX garden_knowledge_emotions ON garden_knowledge_documents USING gin (target_emotions);
