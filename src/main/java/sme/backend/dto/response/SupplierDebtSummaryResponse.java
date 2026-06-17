package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO tổng hợp công nợ NCC.
 * Được tính tại DB level bằng SUM + COUNT DISTINCT, không dùng JS .reduce() ở frontend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierDebtSummaryResponse {
    private BigDecimal totalDebt;
    private BigDecimal totalPaid;
    private BigDecimal totalRemaining;
    private Long suppliersWithDebtCount;
}
