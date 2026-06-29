
package sme.backend.dto.ai;

import java.math.BigDecimal;
import java.util.List;

public record OrderTrackingResponse(
        boolean found,
        String code,
        String statusLabel,
        String createdAt,
        BigDecimal finalAmount,
        String trackingCode,
        String shippingProvider,
        List<Item> items,
        String message
) {
    public record Item(String productName, int quantity, BigDecimal subtotal) {
    }
}
