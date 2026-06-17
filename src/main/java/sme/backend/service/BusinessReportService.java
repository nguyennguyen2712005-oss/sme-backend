package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.response.BusinessReportItem;
import sme.backend.repository.CashbookTransactionRepository;
import sme.backend.repository.InvoiceRepository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BusinessReportService {

    private final InvoiceRepository invoiceRepository;
    private final CashbookTransactionRepository cashbookRepository;

    @Transactional(readOnly = true)
    public List<BusinessReportItem> getBusinessReport(UUID warehouseId, Instant from, Instant to, String period) {
        List<Map<String, Object>> revList;
        List<Map<String, Object>> cashList;

        if ("year".equalsIgnoreCase(period)) {
            revList = invoiceRepository.getRevenueReportYearly(warehouseId, from, to, null, null);
            cashList = cashbookRepository.getOtherIncomeExpenseYearly(warehouseId, from, to);
        } else if ("month".equalsIgnoreCase(period)) {
            revList = invoiceRepository.getRevenueReportMonthly(warehouseId, from, to, null, null);
            cashList = cashbookRepository.getOtherIncomeExpenseMonthly(warehouseId, from, to);
        } else if ("hour".equalsIgnoreCase(period)) {
            revList = invoiceRepository.getRevenueReportHourly(warehouseId, from, to, null, null);
            cashList = cashbookRepository.getOtherIncomeExpenseHourly(warehouseId, from, to);
        } else {
            revList = invoiceRepository.getRevenueReportDaily(warehouseId, from, to, null, null);
            cashList = cashbookRepository.getOtherIncomeExpenseDaily(warehouseId, from, to);
        }

        Map<String, BusinessReportItem> reportMap = new LinkedHashMap<>();

        // Merge Revenue
        for (Map<String, Object> r : revList) {
            String pStr = formatPeriod(r.get("period"), period);
            BigDecimal revenue = getBigDecimal(r.get("revenue"));
            BigDecimal cogs = getBigDecimal(r.get("cogs"));
            BigDecimal grossProfit = getBigDecimal(r.get("gross_profit"));

            BusinessReportItem item = BusinessReportItem.builder()
                    .period(pStr)
                    .revenue(revenue)
                    .cogs(cogs)
                    .grossProfit(grossProfit)
                    .otherIncome(BigDecimal.ZERO)
                    .otherExpenses(BigDecimal.ZERO)
                    .netProfit(BigDecimal.ZERO)
                    .build();
            reportMap.put(pStr, item);
        }

        // Merge Cashbook
        for (Map<String, Object> c : cashList) {
            String pStr = formatPeriod(c.get("period"), period);
            BigDecimal otherIncome = getBigDecimal(c.get("other_income"));
            BigDecimal otherExpenses = getBigDecimal(c.get("other_expenses"));

            BusinessReportItem item = reportMap.computeIfAbsent(pStr, k -> BusinessReportItem.builder()
                    .period(pStr)
                    .revenue(BigDecimal.ZERO)
                    .cogs(BigDecimal.ZERO)
                    .grossProfit(BigDecimal.ZERO)
                    .otherIncome(BigDecimal.ZERO)
                    .otherExpenses(BigDecimal.ZERO)
                    .netProfit(BigDecimal.ZERO)
                    .build());

            item.setOtherIncome(otherIncome);
            item.setOtherExpenses(otherExpenses);
        }

        // Calculate Net Profit
        List<BusinessReportItem> result = new ArrayList<>(reportMap.values());
        result.sort(Comparator.comparing(BusinessReportItem::getPeriod));
        
        for (BusinessReportItem item : result) {
            item.setNetProfit(item.getGrossProfit().add(item.getOtherIncome()).subtract(item.getOtherExpenses()));
        }

        return result;
    }

    private String formatPeriod(Object obj, String period) {
        if (obj == null) return "";
        if (obj instanceof Timestamp) {
            Timestamp ts = (Timestamp) obj;
            if ("year".equalsIgnoreCase(period)) {
                return ts.toLocalDateTime().getYear() + "";
            } else if ("month".equalsIgnoreCase(period)) {
                int y = ts.toLocalDateTime().getYear();
                int m = ts.toLocalDateTime().getMonthValue();
                return String.format("%d-%02d", y, m);
            } else if ("hour".equalsIgnoreCase(period)) {
                int h = ts.toLocalDateTime().getHour();
                return String.format("%02d:00", h);
            } else {
                return ts.toLocalDateTime().toLocalDate().toString(); // YYYY-MM-DD
            }
        }
        return obj.toString();
    }

    private BigDecimal getBigDecimal(Object obj) {
        if (obj == null) return BigDecimal.ZERO;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        if (obj instanceof Number) return new BigDecimal(((Number) obj).doubleValue());
        return new BigDecimal(obj.toString());
    }
}
