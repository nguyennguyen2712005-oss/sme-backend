-- V13: Add approval workflow and person_name to cashbook_transactions
-- Also add debt payment tracking

-- ─── CASHBOOK TRANSACTIONS ───────────────────────────────────────────────────
ALTER TABLE cashbook_transactions ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) DEFAULT 'APPROVED' NOT NULL;
ALTER TABLE cashbook_transactions ADD COLUMN IF NOT EXISTS reject_reason   TEXT;
ALTER TABLE cashbook_transactions ADD COLUMN IF NOT EXISTS person_name     VARCHAR(100);

-- Ensure all existing rows are APPROVED (safety)
UPDATE cashbook_transactions SET approval_status = 'APPROVED' WHERE approval_status IS NULL OR approval_status = '';

-- Index to speed up pending queries
CREATE INDEX IF NOT EXISTS idx_cashbook_approval_status ON cashbook_transactions(approval_status);
CREATE INDEX IF NOT EXISTS idx_cashbook_ref_type_ref_id  ON cashbook_transactions(reference_type, reference_id);
