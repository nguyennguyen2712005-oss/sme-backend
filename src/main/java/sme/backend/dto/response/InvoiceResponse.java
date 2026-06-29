package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class InvoiceResponse {
    private UUID id;
    private String code;
    private UUID shiftId;
    private UUID customerId;
    private String customerName;
    private String customerPhone;
    private String type;
    private BigDecimal totalAmount;
    private BigDecimal discountAmount;
    private BigDecimal volumeDiscountAmt;
    private BigDecimal orderDiscountAmt;
    private BigDecimal couponDiscountAmt;
    private BigDecimal finalAmount;
    private Integer pointsUsed;
    private Integer pointsEarned;
    private String cashierName;
    private UUID warehouseId;
    private String warehouseName;
    private String note;
    private UUID voidedBy;
    private String voidedByName;
    private Instant voidedAt;
    private String voidReason;
    private Instant createdAt;
    private List<ItemResponse> items;
    private List<PaymentResponse> payments;

    @Data
    @Builder
    public static class ItemResponse {
        private UUID productId;
        private String productName;
        private String isbnBarcode;
        private Integer quantity;
        private Integer returnedQuantity;
        private BigDecimal unitPrice;
        private BigDecimal macPrice;
        private BigDecimal subtotal;
    }

    @Data
    @Builder
    public static class PaymentResponse {
        private String method;
        private BigDecimal amount;
        private String reference;
    }
}
