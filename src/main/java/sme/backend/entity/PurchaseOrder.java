package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "purchase_orders")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PurchaseOrder extends BaseEntity {

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "created_by_user_id", nullable = false)
    private UUID createdByUserId;

    /** Role của người tạo phiếu — dùng cho logic duyệt chéo */
    @Column(name = "creator_role", length = 30)
    private String creatorRole;

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

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /**
     * DRAFT           → Mới tạo, chờ gửi duyệt
     * PENDING_APPROVAL → Đã gửi, chờ duyệt (cross-approval)
     * APPROVED        → Đã duyệt, chờ nhận hàng thực tế
     * REJECTED        → Bị từ chối (có lý do)
     * COMPLETED       → Đã nhận hàng, đã nhập kho
     * CANCELLED       → Đã hủy (có lý do)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private PurchaseStatus status = PurchaseStatus.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Lý do hủy phiếu — bắt buộc khi hủy */
    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PurchaseItem> items = new ArrayList<>();

    public enum PurchaseStatus {
        DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, COMPLETED, CANCELLED
    }

    public void addItem(PurchaseItem item) {
        items.add(item);
        item.setPurchaseOrder(this);
    }

    public void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(i -> i.getImportPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
