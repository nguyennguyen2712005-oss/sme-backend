-- V11: Add approval workflow columns to articles table
-- Missing columns: status, rejection_reason, created_by_user_id

ALTER TABLE articles
    ADD COLUMN IF NOT EXISTS status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS rejection_reason    TEXT,
    ADD COLUMN IF NOT EXISTS created_by_user_id  UUID;

-- Backfill: mọi bài viết cũ đang active → PUBLISHED, inactive → DRAFT
UPDATE articles
SET status = CASE
    WHEN is_active = true THEN 'PUBLISHED'
    ELSE 'DRAFT'
END
WHERE status = 'DRAFT' AND created_at < NOW() - INTERVAL '1 minute';
