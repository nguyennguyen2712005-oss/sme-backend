package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "stock_adjustments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class StockAdjustment extends BaseEntity {

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "created_by_user")
    private UUID createdByUser;

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

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /**
     * DRAFT           → Đang nhập liệu kiểm kê (chỉ Manager tạo)
     * PENDING_APPROVAL → Gửi duyệt, chờ Admin duyệt
     * APPROVED        → Đã duyệt, tồn kho đã được điều chỉnh
     * REJECTED        → Bị từ chối (có lý do)
     * CANCELLED       → Đã hủy (có lý do)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private AdjustmentStatus status = AdjustmentStatus.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Lý do hủy phiếu — bắt buộc khi hủy */
    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StockAdjustmentItem> items = new ArrayList<>();

    public enum AdjustmentStatus {
        DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED
    }

    public void addItem(StockAdjustmentItem item) {
        items.add(item);
        item.setAdjustment(this);
    }
}
