-- V12: Hệ thống chiết khấu theo sản lượng (Volume Discount)

-- Bảng quy tắc chiết khấu
CREATE TABLE IF NOT EXISTS discount_rules (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                     VARCHAR(200) NOT NULL,
    warehouse_id             UUID,
    is_active                BOOLEAN NOT NULL DEFAULT true,
    max_cashier_discount_pct NUMERIC(5,2) NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ,
    created_by               VARCHAR(100),
    updated_by               VARCHAR(100)
);

-- Bảng các mốc chiết khấu (ElementCollection)
CREATE TABLE IF NOT EXISTS discount_rule_tiers (
    rule_id      UUID NOT NULL REFERENCES discount_rules(id) ON DELETE CASCADE,
    min_amount   BIGINT NOT NULL,
    discount_pct NUMERIC(5,2) NOT NULL,
    label        VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_discount_rules_warehouse ON discount_rules(warehouse_id);
CREATE INDEX IF NOT EXISTS idx_discount_rule_tiers_rule ON discount_rule_tiers(rule_id);

-- Thêm cột breakdown vào invoices (nullable vì dữ liệu cũ không có)
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS volume_discount_amt NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS order_discount_amt  NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS coupon_discount_amt NUMERIC(19,4) NOT NULL DEFAULT 0;
