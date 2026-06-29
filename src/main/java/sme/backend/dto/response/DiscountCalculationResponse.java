package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class DiscountCalculationResponse {
    private BigDecimal totalAmount;
    private BigDecimal discountPct;
    private BigDecimal discountAmount;
    private String tierLabel;
    private String ruleName;
    private Long nextTierMinAmount;
    private BigDecimal nextTierPct;
    private String nextTierLabel;
}
