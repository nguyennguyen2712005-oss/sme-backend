package sme.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "transfer_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TransferItem extends BaseSimpleEntity {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", nullable = false)
    private InternalTransfer transfer;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "received_qty")
    @Builder.Default
    private Integer receivedQty = 0;

    /** Số lượng chênh lệch (sent - received). > 0 nghĩa là nhận thiếu. */
    @Column(name = "discrepancy_qty", nullable = false)
    @Builder.Default
    private Integer discrepancyQty = 0;

    /** Lý do chênh lệch — bắt buộc khi discrepancyQty > 0 */
    @Column(name = "discrepancy_reason", columnDefinition = "TEXT")
    private String discrepancyReason;
}
