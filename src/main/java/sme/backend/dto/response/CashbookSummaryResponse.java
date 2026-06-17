package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO tổng hợp Thu/Chi cho sổ quỹ.
 * Được tính tại DB level bằng SUM + COALESCE, không dùng JS .reduce() ở frontend.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CashbookSummaryResponse {
    private BigDecimal totalIn;
    private BigDecimal totalOut;
}
