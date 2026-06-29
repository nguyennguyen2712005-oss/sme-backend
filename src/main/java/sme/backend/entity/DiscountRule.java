package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "discount_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscountRule extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "max_cashier_discount_pct", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal maxCashierDiscountPct = BigDecimal.ZERO;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "discount_rule_tiers",
        joinColumns = @JoinColumn(name = "rule_id")
    )
    @OrderBy("min_amount ASC")
    @Builder.Default
    private List<DiscountTier> tiers = new ArrayList<>();
}
