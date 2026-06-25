-- V8: Add approval workflow for purchase orders, transfers, and new stock adjustments

-- ─── PURCHASE ORDERS: new approval columns ───────────────────────────────────
ALTER TABLE purchase_orders
  ADD COLUMN IF NOT EXISTS rejection_reason TEXT,
  ADD COLUMN IF NOT EXISTS rejected_by       VARCHAR(36),
  ADD COLUMN IF NOT EXISTS rejected_at       TIMESTAMP WITH TIME ZONE,
  ADD COLUMN IF NOT EXISTS received_by       VARCHAR(36),
  ADD COLUMN IF NOT EXISTS received_at       TIMESTAMP WITH TIME ZONE;

-- Migrate legacy PENDING → PENDING_APPROVAL
UPDATE purchase_orders SET status = 'PENDING_APPROVAL' WHERE status = 'PENDING';

-- ─── INTERNAL TRANSFERS: new approval columns ────────────────────────────────
ALTER TABLE internal_transfers
  ADD COLUMN IF NOT EXISTS approved_by      VARCHAR(36),
  ADD COLUMN IF NOT EXISTS approved_at      TIMESTAMP WITH TIME ZONE,
  ADD COLUMN IF NOT EXISTS rejected_by      VARCHAR(36),
  ADD COLUMN IF NOT EXISTS rejected_at      TIMESTAMP WITH TIME ZONE,
  ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

-- ─── STOCK ADJUSTMENTS: new table ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stock_adjustments (
  id               UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
  code             VARCHAR(50)              NOT NULL UNIQUE,
  warehouse_id     UUID                     NOT NULL,
  created_by_user  UUID                     NOT NULL,
  approved_by      UUID,
  approved_at      TIMESTAMP WITH TIME ZONE,
  rejected_by      UUID,
  rejected_at      TIMESTAMP WITH TIME ZONE,
  rejection_reason TEXT,
  status           VARCHAR(50)              NOT NULL DEFAULT 'DRAFT',
  note             TEXT,
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
  created_by       VARCHAR(100),
  updated_by       VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS stock_adjustment_items (
  id            UUID                     PRIMARY KEY DEFAULT gen_random_uuid(),
  adjustment_id UUID                     NOT NULL REFERENCES stock_adjustments(id) ON DELETE CASCADE,
  product_id    UUID                     NOT NULL,
  system_qty    INTEGER                  NOT NULL DEFAULT 0,
  actual_qty    INTEGER                  NOT NULL DEFAULT 0,
  diff_qty      INTEGER                  NOT NULL DEFAULT 0,
  reason        TEXT,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
