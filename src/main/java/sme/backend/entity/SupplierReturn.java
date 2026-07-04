package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "supplier_returns")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SupplierReturn extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    /** Tham chiếu phiếu nhập gốc (không bắt buộc) */
    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReturnStatus status = ReturnStatus.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String note;

    // ── Approval fields ──────────────────────────────────────────────────────

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by")
    private UUID rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "shipped_by")
    private UUID shippedBy;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @OneToMany(mappedBy = "supplierReturn", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SupplierReturnItem> items = new ArrayList<>();

    public enum ReturnStatus {
        DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, SHIPPED, CONFIRMED, CANCELLED
    }

    public void addItem(SupplierReturnItem item) {
        items.add(item);
        item.setSupplierReturn(this);
    }

    public void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(SupplierReturnItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
