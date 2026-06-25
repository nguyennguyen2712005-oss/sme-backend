package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "internal_transfers")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class InternalTransfer extends BaseEntity {

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(name = "from_warehouse_id", nullable = false)
    private UUID fromWarehouseId;

    @Column(name = "to_warehouse_id", nullable = false)
    private UUID toWarehouseId;

    @Column(name = "created_by_user_id", nullable = false)
    private UUID createdByUserId;

    /** Role của người tạo phiếu — dùng cho logic duyệt chéo */
    @Column(name = "creator_role", length = 30)
    private String creatorRole;

    @Column(name = "received_by")
    private UUID receivedByUserId;

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

    /**
     * DRAFT              → Mới tạo
     * PENDING_APPROVAL   → Đã gửi duyệt, chờ duyệt (cross-approval)
     * APPROVED           → Đã duyệt, có thể xuất kho
     * REJECTED           → Bị từ chối bởi người duyệt
     * DISPATCHED         → Đã xuất kho nguồn, hàng đang trên đường
     * RECEIVED           → Kho đích đã nhận đủ hàng
     * RECEIVED_PARTIAL   → Kho đích nhận thiếu (có chênh lệch)
     * REJECTED_BY_RECEIVER → Kho đích từ chối nhận, hàng hoàn về kho nguồn
     * CANCELLED          → Đã hủy
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private TransferStatus status = TransferStatus.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String note;

    /** Lý do hủy / kho nhập từ chối — bắt buộc nhập */
    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "dispatched_by", length = 36)
    private String dispatchedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true)
    @org.hibernate.annotations.BatchSize(size = 50)
    @Builder.Default
    private List<TransferItem> items = new ArrayList<>();

    public enum TransferStatus {
        DRAFT, PENDING_APPROVAL, APPROVED, REJECTED,
        DISPATCHED, RECEIVED, RECEIVED_PARTIAL, REJECTED_BY_RECEIVER,
        CANCELLED
    }

    public void addItem(TransferItem item) {
        items.add(item);
        item.setTransfer(this);
    }

    @Column(name = "reference_order_id")
    private UUID referenceOrderId;

    @Column(name = "transfer_reason")
    private String transferReason;
}
