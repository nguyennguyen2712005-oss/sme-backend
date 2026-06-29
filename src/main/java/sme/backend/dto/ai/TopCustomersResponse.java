
package sme.backend.dto.ai;

import java.math.BigDecimal;
import java.util.List;

public record TopCustomersResponse(
        List<Item> customers
) {
    public record Item(String fullName, String customerTier, BigDecimal totalSpent, Integer loyaltyPoints) {
    }
}
