package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.envers.NotAudited;

/**
 * Hóa đơn POS (Offline).
 * Bắt buộc gắn với một Shift đang OPEN.
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice extends BaseEntity {

    @Column(unique = true, nullable = false, length = 50)
    private String code;

    @Column(name = "shift_id", nullable = false)
    private UUID shiftId;

    @Column(name = "warehouse_id")
    @NotAudited
    private UUID warehouseId;

    /** NULL nếu là khách vãng lai (không có tài khoản) */
    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private InvoiceType type = InvoiceType.SALE;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "volume_discount_amt", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal volumeDiscountAmt = BigDecimal.ZERO;

    @Column(name = "order_discount_amt", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal orderDiscountAmt = BigDecimal.ZERO;

    @Column(name = "coupon_discount_amt", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal couponDiscountAmt = BigDecimal.ZERO;

    @Column(name = "final_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal finalAmount = BigDecimal.ZERO;

    @Column(name = "points_used")
    @Builder.Default
    private Integer pointsUsed = 0;

    @Column(name = "points_earned")
    @Builder.Default
    private Integer pointsEarned = 0;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    /** ID hóa đơn gốc (nếu đây là hóa đơn trả hàng RETURN) */
    @Column(name = "return_of_id")
    private UUID returnOfId;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(columnDefinition = "TEXT")
    private String note;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @org.hibernate.annotations.BatchSize(size = 50)
    @Builder.Default
    private List<InvoiceItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @org.hibernate.annotations.BatchSize(size = 50)
    @Builder.Default
    private List<InvoicePayment> payments = new ArrayList<>();

    public enum InvoiceType {
        SALE,
        RETURN,
        VOIDED
    }

    /** Đồng bộ 2 chiều quan hệ invoice <-> items */
    public void addItem(InvoiceItem item) {
        items.add(item);
        item.setInvoice(this);
    }

    /** Đồng bộ 2 chiều quan hệ invoice <-> payments */
    public void addPayment(InvoicePayment payment) {
        payments.add(payment);
        payment.setInvoice(this);
    }

    /** Tính lại total từ các items */
    public void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(InvoiceItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs(); // Với RETURN, các quantity âm → tổng âm → lấy abs
        this.finalAmount = this.totalAmount.subtract(this.discountAmount);
    }
}
