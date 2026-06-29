
package sme.backend.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import sme.backend.dto.ai.*;
import sme.backend.dto.response.BusinessReportItem;
import sme.backend.dto.response.LowStockItem;
import sme.backend.entity.Customer;
import sme.backend.repository.CustomerRepository;
import sme.backend.repository.InventoryRepository;
import sme.backend.service.BusinessReportService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

@Configuration
public class AiConfig {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        Bạn là AI Co-pilot của hệ thống SME ERP & POS.
                        Hãy trả lời bằng tiếng Việt, ngắn gọn và chính xác.
                        Khi câu hỏi liên quan đến số liệu kinh doanh thật (doanh thu, tồn kho, khách hàng),
                        hãy luôn gọi công cụ (function) tương ứng để lấy số liệu thật, không tự suy đoán.
                        """)
                .defaultFunctions(
                        "getTodayRevenueFunction",
                        "getLowStockProductsFunction",
                        "getTopCustomersFunction")
                .build();
    }

    // ─────────────────────────────────────────────────────────
    // TOOL 1: DOANH THU / LỢI NHUẬN HÔM NAY (tất cả chi nhánh)
    // ─────────────────────────────────────────────────────────
    @Bean
    @Description("Lấy báo cáo doanh thu, lợi nhuận của cửa hàng tính từ đầu ngày hôm nay đến hiện tại, gộp tất cả chi nhánh")
    public Function<TodayRevenueRequest, TodayRevenueResponse> getTodayRevenueFunction(
            BusinessReportService businessReportService) {
        return request -> {
            LocalDate today = LocalDate.now(VN_ZONE);
            Instant from = today.atStartOfDay(VN_ZONE).toInstant();
            Instant to = today.plusDays(1).atStartOfDay(VN_ZONE).toInstant();

            List<BusinessReportItem> report = businessReportService.getBusinessReport(null, from, to, "day");

            BigDecimal revenue = BigDecimal.ZERO;
            BigDecimal grossProfit = BigDecimal.ZERO;
            BigDecimal netProfit = BigDecimal.ZERO;
            BigDecimal otherIncome = BigDecimal.ZERO;
            BigDecimal otherExpenses = BigDecimal.ZERO;
            for (BusinessReportItem item : report) {
                revenue = revenue.add(item.getRevenue());
                grossProfit = grossProfit.add(item.getGrossProfit());
                netProfit = netProfit.add(item.getNetProfit());
                otherIncome = otherIncome.add(item.getOtherIncome());
                otherExpenses = otherExpenses.add(item.getOtherExpenses());
            }

            return new TodayRevenueResponse(
                    today.toString(), revenue, grossProfit, netProfit, otherIncome, otherExpenses,
                    "Số liệu tổng hợp từ tất cả chi nhánh, tính đến thời điểm hiện tại trong ngày.");
        };
    }

    // ─────────────────────────────────────────────────────────
    // TOOL 2: SẢN PHẨM TỒN KHO THẤP (tất cả chi nhánh)
    // ─────────────────────────────────────────────────────────
    @Bean
    @Description("Lấy danh sách sản phẩm đang tồn kho thấp / sắp hết hàng, gộp tất cả chi nhánh, sắp xếp từ số lượng ít nhất")
    public Function<LowStockRequest, LowStockResponse> getLowStockProductsFunction(
            InventoryRepository inventoryRepository) {
        return request -> {
            int limit = (request.limit() != null && request.limit() > 0) ? request.limit() : 10;
            List<LowStockItem> all = inventoryRepository.findLowStockWithNameByWarehouse(null);

            List<LowStockResponse.Item> items = all.stream()
                    .limit(limit)
                    .map(i -> new LowStockResponse.Item(
                            i.getProductName(), i.getWarehouseName(), i.getQuantity(), i.getMinQuantity()))
                    .toList();

            return new LowStockResponse(all.size(), items);
        };
    }

    // ─────────────────────────────────────────────────────────
    // TOOL 3: KHÁCH HÀNG THÂN THIẾT / CHI TIÊU NHIỀU NHẤT
    // ─────────────────────────────────────────────────────────
    @Bean
    @Description("Lấy danh sách khách hàng thân thiết, chi tiêu nhiều nhất tại cửa hàng (xếp hạng theo tổng chi tiêu)")
    public Function<TopCustomersRequest, TopCustomersResponse> getTopCustomersFunction(
            CustomerRepository customerRepository) {
        return request -> {
            int limit = (request.limit() != null && request.limit() > 0) ? request.limit() : 5;
            Page<Customer> page = customerRepository.findTopCustomers(PageRequest.of(0, limit));

            List<TopCustomersResponse.Item> items = page.getContent().stream()
                    .map(c -> new TopCustomersResponse.Item(
                            c.getFullName(),
                            c.getCustomerTier() != null ? c.getCustomerTier().name() : "STANDARD",
                            c.getTotalSpent(),
                            c.getLoyaltyPoints()))
                    .toList();

            return new TopCustomersResponse(items);
        };
    }
}
