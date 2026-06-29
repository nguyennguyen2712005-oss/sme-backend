package sme.backend.dto.request;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class DiscountRuleRequest {
    private String name;
    private UUID warehouseId;
    private Boolean isActive;
    private BigDecimal maxCashierDiscountPct;
    private List<TierRequest> tiers;

    @Data
    public static class TierRequest {
        private Long minAmount;
        private BigDecimal discountPct;
        private String label;
    }
}
