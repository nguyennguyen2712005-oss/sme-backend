package sme.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;
import java.math.BigDecimal;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscountTier {

    @Column(name = "min_amount")
    private Long minAmount;

    @Column(name = "discount_pct", precision = 5, scale = 2)
    private BigDecimal discountPct;

    @Column(name = "label", length = 100)
    private String label;
}
