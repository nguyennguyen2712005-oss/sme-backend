package sme.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "stock_adjustment_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class StockAdjustmentItem extends BaseSimpleEntity {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjustment_id", nullable = false)
    private StockAdjustment adjustment;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "system_qty", nullable = false)
    @Builder.Default
    private Integer systemQty = 0;

    @Column(name = "actual_qty", nullable = false)
    @Builder.Default
    private Integer actualQty = 0;

    @Column(name = "diff_qty", nullable = false)
    @Builder.Default
    private Integer diffQty = 0;

    @Column(columnDefinition = "TEXT")
    private String reason;

    /** Phân loại lý do chênh lệch — giúp thống kê */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", length = 30)
    private AdjustmentReasonType reasonType;
}
