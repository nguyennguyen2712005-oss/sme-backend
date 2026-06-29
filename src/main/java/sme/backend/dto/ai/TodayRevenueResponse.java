
package sme.backend.dto.ai;

import java.math.BigDecimal;

public record TodayRevenueResponse(
        String date,
        BigDecimal revenue,
        BigDecimal grossProfit,
        BigDecimal netProfit,
        BigDecimal otherIncome,
        BigDecimal otherExpenses,
        String note
) {
}
