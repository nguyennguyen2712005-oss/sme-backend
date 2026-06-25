package sme.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "purchase_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PurchaseItem extends BaseSimpleEntity {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "received_qty")
    @Builder.Default
    private Integer receivedQty = 0;

    @Column(name = "import_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal importPrice;

    /** Ghi chú khi nhận từng mặt hàng (VD: hàng lỗi, đóng gói khác...) */
    @Column(name = "receive_note", columnDefinition = "TEXT")
    private String receiveNote;
}
