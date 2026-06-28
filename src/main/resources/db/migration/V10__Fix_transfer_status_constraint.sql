-- V10: Fix internal_transfers status check constraint
-- Constraint cũ chỉ có DRAFT, DISPATCHED, RECEIVED, CANCELLED
-- Cần bổ sung: PENDING_APPROVAL, APPROVED, REJECTED, RECEIVED_PARTIAL, REJECTED_BY_RECEIVER

ALTER TABLE internal_transfers DROP CONSTRAINT IF EXISTS internal_transfers_status_check;

ALTER TABLE internal_transfers ADD CONSTRAINT internal_transfers_status_check
    CHECK (status IN (
        'DRAFT',
        'PENDING_APPROVAL',
        'APPROVED',
        'REJECTED',
        'DISPATCHED',
        'RECEIVED',
        'RECEIVED_PARTIAL',
        'REJECTED_BY_RECEIVER',
        'CANCELLED'
    ));
