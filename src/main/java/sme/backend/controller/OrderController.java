package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreateOrderRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.OrderResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.entity.Customer;
import sme.backend.entity.Order;
import sme.backend.entity.User;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.CustomerRepository;
import sme.backend.security.UserPrincipal;
import sme.backend.service.OrderService;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final CustomerRepository customerRepository;

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> createOrder(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateOrderRequest req) {

        // Nếu customerId null và có principal (Storefront đang login với role CUSTOMER)
        if (req.getCustomerId() == null && principal != null && "ROLE_CUSTOMER".equals(principal.getRole().name())) {
             Customer customer = customerRepository.findByUserId(principal.getId())
                 .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
             req.setCustomerId(customer.getId());
        }

        if (req.getCustomerId() == null) {
            throw new BusinessException("MISSING_CUSTOMER", "Phải đăng nhập hoặc cung cấp customerId");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(orderService.createOrder(req)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<OrderResponse>>> getOrders(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword, 
            @RequestParam(required = false) Instant fromDate,
            @RequestParam(required = false) Instant toDate,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID warehouseId) {

        UUID wid = (principal.getRole() == User.UserRole.ROLE_ADMIN) && warehouseId != null ? warehouseId : principal.getWarehouseId();

        Order.OrderStatus orderStatus = null;
        if (status != null && !status.isBlank() && !status.equals("ALL")) {
            try { orderStatus = Order.OrderStatus.valueOf(status.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }

        Order.OrderType orderType = null;
        if (type != null && !type.isBlank() && !type.equals("ALL")) {
            try { orderType = Order.OrderType.valueOf(type.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }

        Order.PaymentStatus pStatus = null;
        if (paymentStatus != null && !paymentStatus.isBlank() && !paymentStatus.equals("ALL")) {
            try { pStatus = Order.PaymentStatus.valueOf(paymentStatus.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }

        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(orderService.getOrders(wid, orderStatus, orderType, keyword, fromDate, toDate, pStatus, paymentMethod, provinceCode, pageable))));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOrderStats(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Instant fromDate,
            @RequestParam(required = false) Instant toDate,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String source) {

        UUID wid = (principal.getRole() == User.UserRole.ROLE_ADMIN) && warehouseId != null ? warehouseId : principal.getWarehouseId();

        Order.OrderStatus orderStatus = null;
        if (status != null && !status.isBlank() && !status.equals("ALL")) {
            try { orderStatus = Order.OrderStatus.valueOf(status.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }

        Order.OrderType orderType = null;
        if (type != null && !type.isBlank() && !type.equals("ALL")) {
            try { orderType = Order.OrderType.valueOf(type.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }

        Order.PaymentStatus pStatus = null;
        if (paymentStatus != null && !paymentStatus.isBlank() && !paymentStatus.equals("ALL")) {
            try { pStatus = Order.PaymentStatus.valueOf(paymentStatus.toUpperCase()); } catch (IllegalArgumentException ignored) {}
        }

        var stats = orderService.getOrderStats(wid, orderStatus, orderType, keyword, fromDate, toDate, pStatus, paymentMethod, provinceCode, source);
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getPendingOrders(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId) {
        UUID wid = (principal.getRole() == User.UserRole.ROLE_ADMIN) && warehouseId != null ? warehouseId : principal.getWarehouseId();
        return ResponseEntity.ok(ApiResponse.ok(orderService.getPendingOrders(wid)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> getOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.getOrderDetail(id)));
    }

    @PostMapping("/suggest-branch")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> suggestBranchForOrder(@RequestBody CreateOrderRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                orderService.suggestBranchesForOrder(req.getProvinceCode(), req.getShippingLatitude(), req.getShippingLongitude(), req.getItems())));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> updateStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String newStatus      = body.get("status");
        String note           = body.get("note");
        String trackingCode   = body.get("trackingCode");
        String shippingProvider = body.get("shippingProvider");

        OrderResponse updated = orderService.updateStatus(id, newStatus, note, trackingCode, shippingProvider, principal.getId().toString());
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật trạng thái thành công", updated));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CUSTOMER','CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> cancelOrder(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        String reason = body.get("reason");
        String changedBy = principal != null ? principal.getId().toString() : "SYSTEM";
        
        if (principal != null && "ROLE_CUSTOMER".equals(principal.getRole().name())) {
             OrderResponse order = orderService.getOrderDetail(id);
             Customer customer = customerRepository.findByUserId(principal.getId()).orElse(null);
             if (customer == null || order.getCustomerId() == null || !customer.getId().equals(order.getCustomerId())) {
                 throw new BusinessException("FORBIDDEN", "Không được phép hủy đơn hàng này.");
             }
        }
        
        OrderResponse updated = orderService.updateStatus(id, "CANCELLED", reason, null, null, changedBy);
        return ResponseEntity.ok(ApiResponse.ok("Hủy đơn hàng thành công", updated));
    }

    @PatchMapping("/{id}/assign-warehouse")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<OrderResponse>> assignWarehouse(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String warehouseIdStr = body.get("warehouseId");
        String reason = body.get("reason");
        if (warehouseIdStr == null || warehouseIdStr.isBlank()) {
            throw new BusinessException("INVALID_DATA", "Thiếu warehouseId");
        }
        UUID warehouseId = UUID.fromString(warehouseIdStr);
        OrderResponse updated = orderService.assignWarehouse(id, warehouseId, reason, principal.getId().toString());
        return ResponseEntity.ok(ApiResponse.ok("Chuyển kho thành công", updated));
    }
}