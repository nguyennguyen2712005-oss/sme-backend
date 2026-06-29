package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sổ quỹ - Kế toán kép đơn giản hóa.
 * Mọi luồng tiền vào/ra đều được ghi vào đây.
 */
@Entity
@Table(name = "cashbook_transactions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CashbookTransaction extends BaseSimpleEntity {

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** NULL nếu là giao dịch do Admin tạo ngoài ca làm việc */
    @Column(name = "shift_id")
    private UUID shiftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fund_type", nullable = false, length = 20)
    private FundType fundType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    /**
     * Loại chứng từ nguồn:
     * INVOICE            → Hóa đơn POS
     * SALE_ONLINE        → Đơn hàng Online đã giao
     * PURCHASE_ORDER     → Phiếu nhập kho
     * SUPPLIER_PAYMENT   → Trả nợ NCC
     * COD_RECONCILIATION → Đối soát tiền COD
     * EXPENSE            → Chi phí vận hành
     * OTHER_INCOME       → Thu nhập khác
     * MANUAL             → Thủ công
     */
    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_before", precision = 19, scale = 4)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "person_name", length = 100)
    private String personName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20, nullable = false)
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    public enum FundType {
        CASH_111,
        BANK_112
    }

    public enum TransactionType {
        IN,
        OUT
    }

    public enum ApprovalStatus {
        PENDING, APPROVED, REJECTED
    }
}
