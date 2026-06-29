package sme.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class CheckoutRequest {

    @NotNull(message = "shiftId bắt buộc")
    private UUID shiftId;

    private UUID customerId;       // null = khách vãng lai

    @NotEmpty(message = "Giỏ hàng không được rỗng")
    @Valid
    private List<CartItemRequest> items;

    @NotEmpty(message = "Phải có ít nhất 1 phương thức thanh toán")
    @Valid
    private List<PaymentRequest> payments;

    private Integer pointsToUse;   // null hoặc 0 = không dùng điểm

    private BigDecimal volumeDiscountAmt;
    private BigDecimal orderDiscountAmt;
    private BigDecimal couponDiscountAmt;
    private List<String> couponCodes; // Support multiple coupon codes

    private String note;

    private Double shippingLatitude;
    private Double shippingLongitude;

    @Data
    public static class CartItemRequest {
        @NotNull(message = "productId bắt buộc")
        private UUID productId;

        @NotNull @Min(1)
        private Integer quantity;

        @NotNull @DecimalMin("0")
        private BigDecimal unitPrice;
    }

    @Data
    public static class PaymentRequest {
        @NotBlank(message = "Phương thức thanh toán bắt buộc")
        private String method;     // CASH, CARD, MOMO, VNPAY

        @NotNull @DecimalMin(value = "0.01", message = "Số tiền phải > 0")
        private BigDecimal amount;

        private String reference;  // Mã giao dịch từ cổng (optional)
    }
}
