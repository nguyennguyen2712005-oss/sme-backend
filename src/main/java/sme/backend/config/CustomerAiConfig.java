
package sme.backend.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import sme.backend.dto.ai.*;
import sme.backend.dto.response.OrderResponse;
import sme.backend.dto.response.PromotionResponse;
import sme.backend.entity.Order;
import sme.backend.entity.Product;
import sme.backend.entity.Category;
import sme.backend.repository.ProductRepository;
import sme.backend.repository.CategoryRepository;
import sme.backend.repository.OrderRepository;
import sme.backend.service.OrderService;
import sme.backend.service.PromotionService;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;

@Configuration
public class CustomerAiConfig {

    @Bean("customerChatClient")
    public ChatClient customerChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        Bạn là trợ lý tư vấn khách hàng của nhà sách trực tuyến.
                        Nhiệm vụ của bạn:
                        - Giúp khách hàng tìm kiếm sách theo tên, tác giả, thể loại.
                        - Cung cấp thông tin tóm tắt về sách và giá bán nếu khách hàng yêu cầu.
                        - Tra cứu trạng thái đơn hàng khi khách cung cấp mã đơn hàng.
                        - Cho biết các chương trình khuyến mãi/mã giảm giá đang áp dụng khi khách hỏi.
                        - Trả lời bằng tiếng Việt, giọng điệu nhiệt tình, lịch sự và thân thiện.
                        - Gợi ý thêm các đầu sách tương tự nếu thấy phù hợp.

                        NGUYÊN TẮC BẢO MẬT:
                        - TUYỆT ĐỐI KHÔNG chia sẻ thông tin về giá nhập (macPrice), lợi nhuận, chi phí quản lý, tồn kho thực tế ở các chi nhánh, thông tin người dùng khác hay các số liệu nhạy cảm của cửa hàng.
                        - Chỉ sử dụng các công cụ tìm kiếm/tra cứu được cung cấp để lấy thông tin sản phẩm, danh mục, đơn hàng, khuyến mãi. Không tự ý bịa đặt tên sách, trạng thái đơn hàng hay khuyến mãi không có thật.
                        - Để tra đơn hàng, PHẢI hỏi khách mã đơn hàng (ví dụ ORD-...) trước khi gọi công cụ tra cứu. Không suy đoán mã đơn hàng.
                        - Nếu không tìm thấy sách/đơn hàng/khuyến mãi khách yêu cầu, hãy xin lỗi và giới thiệu hướng hỗ trợ khác.
                        """)
                .defaultFunctions(
                        "searchProductsFunction",
                        "getCategoriesFunction",
                        "getTopSellingProductsFunction",
                        "trackOrderFunction",
                        "getActivePromotionsFunction")
                .build();
    }

    @Bean
    @Description("Tìm kiếm sách, sản phẩm trong cửa hàng theo từ khóa (tên sách, thể loại, tác giả)")
    public Function<ProductSearchRequest, ProductSearchResponse> searchProductsFunction(ProductRepository productRepository) {
        return request -> {
            String keyword = request.keyword() != null ? request.keyword() : "";
            Page<Product> page = productRepository.searchByKeyword(keyword, PageRequest.of(0, 10));
            List<ProductSearchResponse.ProductDto> products = page.getContent().stream()
                    .map(p -> new ProductSearchResponse.ProductDto(
                            p.getName(),
                            p.getRetailPrice(),
                            p.getDescription() != null && p.getDescription().length() > 200 
                                    ? p.getDescription().substring(0, 200) + "..." 
                                    : p.getDescription()
                    ))
                    .toList();
            return new ProductSearchResponse(products);
        };
    }

    @Bean
    @Description("Lấy danh sách tất cả các thể loại sách (danh mục) đang có bán tại cửa hàng")
    public Function<CategorySearchRequest, CategorySearchResponse> getCategoriesFunction(CategoryRepository categoryRepository) {
        return request -> {
            List<String> categories = categoryRepository.findByIsActiveTrueOrderBySortOrder().stream()
                    .map(Category::getName)
                    .toList();
            return new CategorySearchResponse(categories);
        };
    }

    @Bean
    @Description("Lấy danh sách các sách bán chạy nhất tại cửa hàng (Top selling books)")
    public Function<TopSellingProductsRequest, ProductSearchResponse> getTopSellingProductsFunction(ProductRepository productRepository) {
        return request -> {
            int limit = (request.limit() != null && request.limit() > 0) ? request.limit() : 5;
            // Get best selling products in the last 30 days
            Instant fromDate = Instant.now().minus(30, ChronoUnit.DAYS);
            Instant toDate = Instant.now();
            List<Map<String, Object>> topProducts = productRepository.findTopSellingProducts(null, fromDate, toDate, limit, null);
            
            List<ProductSearchResponse.ProductDto> products = topProducts.stream()
                    .map(map -> {
                        String name = (String) map.get("name");
                        return new ProductSearchResponse.ProductDto(name, BigDecimal.ZERO, "Sách bán chạy");
                    })
                    .toList();
            return new ProductSearchResponse(products);
        };
    }

    // ─────────────────────────────────────────────────────────
    // TOOL MỚI 1: TRA CỨU ĐƠN HÀNG THEO MÃ
    // Theo đúng mô hình bảo mật đã có ở trang tra cứu đơn hàng công khai
    // (OrderTrackingPage): biết mã đơn hàng = đủ điều kiện xem trạng thái.
    // Để giảm rò rỉ PII qua kênh chat, KHÔNG trả SĐT/địa chỉ giao hàng,
    // chỉ trả các field cần để khách biết "đơn của tôi tới đâu rồi".
    // ─────────────────────────────────────────────────────────
    @Bean
    @Description("Tra cứu trạng thái, sản phẩm và mã vận đơn của một đơn hàng theo mã đơn hàng khách cung cấp")
    public Function<OrderTrackingRequest, OrderTrackingResponse> trackOrderFunction(
            OrderRepository orderRepository, OrderService orderService) {
        return request -> {
            String rawCode = request.orderCode() != null ? request.orderCode().trim() : "";
            if (rawCode.isBlank()) {
                return new OrderTrackingResponse(false, null, null, null, null, null, null,
                        List.of(), "Vui lòng cho mình biết mã đơn hàng (ví dụ: ORD-...) để tra cứu giúp bạn nhé.");
            }

            return orderRepository.findByCode(rawCode.toUpperCase())
                    .map(order -> toTrackingResponse(order, orderService))
                    .orElseGet(() -> new OrderTrackingResponse(false, rawCode, null, null, null, null, null,
                            List.of(), "Mình không tìm thấy đơn hàng với mã \"" + rawCode + "\". Bạn vui lòng kiểm tra lại mã đơn hàng giúp mình nhé."));
        };
    }

    private OrderTrackingResponse toTrackingResponse(Order order, OrderService orderService) {
        OrderResponse full = orderService.mapToResponse(order);

        List<OrderTrackingResponse.Item> items = full.getItems() == null ? List.of()
                : full.getItems().stream()
                        .map(i -> new OrderTrackingResponse.Item(i.getProductName(), i.getQuantity(), i.getSubtotal()))
                        .toList();

        return new OrderTrackingResponse(
                true,
                full.getCode(),
                translateOrderStatus(full.getStatus()),
                full.getCreatedAt() != null ? full.getCreatedAt().toString() : null,
                full.getFinalAmount(),
                full.getTrackingCode(),
                full.getShippingProvider(),
                items,
                null);
    }

    private String translateOrderStatus(String status) {
        if (status == null) return "Không xác định";
        return switch (status) {
            case "PAYMENT_PENDING" -> "Chờ thanh toán";
            case "PENDING" -> "Chờ xử lý";
            case "PACKING" -> "Đang đóng gói";
            case "WAITING_FOR_CONSOLIDATION" -> "Đang chờ ghép đơn vận chuyển";
            case "SHIPPING" -> "Đang giao hàng";
            case "DELIVERED" -> "Đã giao thành công";
            case "CANCELLED" -> "Đã hủy";
            case "RETURNED" -> "Đã hoàn trả";
            default -> status;
        };
    }

    // ─────────────────────────────────────────────────────────
    // TOOL MỚI 2: KHUYẾN MÃI ĐANG ÁP DỤNG CHO KHÁCH ONLINE
    // Tái dùng PromotionService.getActivePromotions() đã có,
    // lọc thêm theo applicableChannel để không gợi ý nhầm khuyến mãi
    // chỉ áp dụng tại POS (cửa hàng) cho khách đang chat online.
    // ─────────────────────────────────────────────────────────
    @Bean
    @Description("Lấy danh sách chương trình khuyến mãi, mã giảm giá đang áp dụng cho khách mua hàng online tại cửa hàng")
    public Function<ActivePromotionsRequest, ActivePromotionsResponse> getActivePromotionsFunction(
            PromotionService promotionService) {
        return request -> {
            List<PromotionResponse> active = promotionService.getActivePromotions();

            List<ActivePromotionsResponse.Item> items = active.stream()
                    .filter(p -> !"POS".equalsIgnoreCase(p.getApplicableChannel()))
                    .map(p -> new ActivePromotionsResponse.Item(
                            p.getCode(),
                            p.getName(),
                            p.getDescription(),
                            p.getDiscountType(),
                            p.getDiscountValue(),
                            p.getMinOrderValue(),
                            p.getEndDate()))
                    .toList();

            return new ActivePromotionsResponse(items);
        };
    }
}
