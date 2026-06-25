-- V9: Approval workflow v2 — cross-approval fields, RECEIVED_PARTIAL, REJECTED_BY_RECEIVER, AdjustmentReasonType

-- ─── PURCHASE ORDERS ─────────────────────────────────────────────────────────
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS cancel_reason TEXT;
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS creator_role  VARCHAR(30);

-- ─── PURCHASE ITEMS ──────────────────────────────────────────────────────────
ALTER TABLE purchase_items ADD COLUMN IF NOT EXISTS receive_note TEXT;

-- ─── INTERNAL TRANSFERS ──────────────────────────────────────────────────────
ALTER TABLE internal_transfers ADD COLUMN IF NOT EXISTS dispatched_by VARCHAR(36);
ALTER TABLE internal_transfers ADD COLUMN IF NOT EXISTS cancel_reason TEXT;
ALTER TABLE internal_transfers ADD COLUMN IF NOT EXISTS creator_role  VARCHAR(30);

-- ─── TRANSFER ITEMS ──────────────────────────────────────────────────────────
ALTER TABLE transfer_items ADD COLUMN IF NOT EXISTS discrepancy_qty    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE transfer_items ADD COLUMN IF NOT EXISTS discrepancy_reason TEXT;

-- ─── STOCK ADJUSTMENTS ───────────────────────────────────────────────────────
ALTER TABLE stock_adjustments ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE stock_adjustments ADD COLUMN IF NOT EXISTS cancel_reason TEXT;

-- ─── STOCK ADJUSTMENT ITEMS ──────────────────────────────────────────────────
ALTER TABLE stock_adjustment_items ADD COLUMN IF NOT EXISTS reason_type VARCHAR(30);
