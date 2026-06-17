package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.response.ApiResponse;
import sme.backend.entity.User;
import sme.backend.repository.InventoryRepository;
import sme.backend.repository.InvoiceRepository;
import sme.backend.repository.ProductRepository;
import sme.backend.repository.CustomerRepository;
import sme.backend.security.UserPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final InvoiceRepository invoiceRepository;
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final sme.backend.service.BusinessReportService businessReportService;

    // Hàm tiện ích để đảm bảo Manager luôn chỉ truy cập được kho của họ
    private UUID getEffectiveWarehouseId(UserPrincipal principal, UUID requestedWarehouseId) {
        if (principal.getRole() == User.UserRole.ROLE_ADMIN) {
            return requestedWarehouseId;
        }
        return principal.getWarehouseId();
    }

    @GetMapping("/revenue")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRevenueReport(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) UUID cashierId) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        List<Map<String, Object>> result;
        if ("year".equalsIgnoreCase(period)) {
            result = invoiceRepository.getRevenueReportYearly(wid, from, to, paymentMethod, cashierId);
        } else if ("month".equalsIgnoreCase(period)) {
            result = invoiceRepository.getRevenueReportMonthly(wid, from, to, paymentMethod, cashierId);
        } else if ("week".equalsIgnoreCase(period)) {
            result = invoiceRepository.getRevenueReportWeekly(wid, from, to, paymentMethod, cashierId);
        } else {
            result = invoiceRepository.getRevenueReportDaily(wid, from, to, paymentMethod, cashierId);
        }

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/business")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<sme.backend.dto.response.BusinessReportItem>>> getBusinessReport(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(required = false) UUID warehouseId) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);
        return ResponseEntity.ok(ApiResponse.ok(
                businessReportService.getBusinessReport(wid, from, to, period)));
    }

    @GetMapping("/inventory-value")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getInventoryValue(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(
                inventoryRepository.getInventoryValueReport(wid)));
    }

    @GetMapping("/dead-stock")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getDeadStock(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(required = false) UUID warehouseId) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(
                inventoryRepository.findDeadStockByWarehouse(wid, days)));
    }

    @GetMapping("/top-products")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopProducts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String paymentMethod) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(
                productRepository.findTopSellingProducts(wid, from, to, limit, paymentMethod)));
    }

    @GetMapping("/top-customers")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopCustomers(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "revenue") String metric,
            @RequestParam(defaultValue = "10") int limit) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        return ResponseEntity.ok(ApiResponse.ok(
                customerRepository.findTopCustomersByTimeRange(wid, from, to, metric, limit)));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardSummary(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId) {

        UUID wid = getEffectiveWarehouseId(principal, warehouseId);

        Instant todayStart = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant todayEnd = todayStart.plus(1, java.time.temporal.ChronoUnit.DAYS);

        List<Map<String, Object>> revenue = invoiceRepository.getRevenueReportDaily(wid, todayStart, todayEnd, null, null);

        int lowStockCount = (wid != null) ? inventoryRepository.findLowStockByWarehouse(wid).size() : 0;

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "warehouseId", wid != null ? wid.toString() : "ALL",
                "revenueToday", revenue,
                "lowStockCount", lowStockCount)));
    }

    @GetMapping("/new-customers")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Long>> getNewCustomersCount(
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return ResponseEntity.ok(ApiResponse.ok(customerRepository.countNewCustomers(from, to)));
    }
}