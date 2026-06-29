
package sme.backend.dto.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ActivePromotionsResponse(
        List<Item> promotions
) {
    public record Item(
            String code,
            String name,
            String description,
            String discountType,
            BigDecimal discountValue,
            BigDecimal minOrderValue,
            Instant endDate) {
    }
}
