-- V6: Composite indexes cho Finance module
-- Mục đích: Tối ưu performance cho các truy vấn SUM/aggregate real-time
-- trên sổ quỹ (cashbook) và công nợ NCC (supplier_debts).

-- Cashbook: tối ưu bộ lọc (warehouse + ngày + loại giao dịch)
CREATE INDEX IF NOT EXISTS idx_cashbook_wh_created_txntype
    ON cashbook_transactions (warehouse_id, created_at, transaction_type);

-- Supplier Debts: tối ưu aggregate SUM remaining + COUNT DISTINCT supplier
CREATE INDEX IF NOT EXISTS idx_supplier_debts_wh_remaining_supplier
    ON supplier_debts (remaining_amount, supplier_id);
