package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.response.ApiResponse;
import sme.backend.entity.SupplierReturn;
import sme.backend.security.UserPrincipal;
import sme.backend.service.SupplierReturnService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/supplier-returns")
@RequiredArgsConstructor
public class SupplierReturnController {

    private final SupplierReturnService supplierReturnService;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        SupplierReturn.ReturnStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = SupplierReturn.ReturnStatus.valueOf(status.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        Page<SupplierReturn> paged = supplierReturnService.search(supplierId, warehouseId, statusEnum,
                PageRequest.of(page, size));

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "content", paged.getContent().stream().map(this::toSummary).toList(),
                "totalElements", paged.getTotalElements(),
                "totalPages", paged.getTotalPages(),
                "currentPage", paged.getNumber()
        )));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getById(@PathVariable UUID id) {
        SupplierReturn sr = supplierReturnService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(toDetail(sr)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody CreateRequest body,
            @AuthenticationPrincipal UserPrincipal principal) {

        List<SupplierReturnService.ItemRequest> items = body.items().stream()
                .map(i -> new SupplierReturnService.ItemRequest(
                        i.productId(), i.quantity(), i.unitPrice(), i.returnReason()))
                .toList();

        SupplierReturn sr = supplierReturnService.create(
                body.supplierId(), body.warehouseId(), body.purchaseOrderId(),
                body.note(), items, principal.getId());

        return ResponseEntity.ok(ApiResponse.ok("Tạo phiếu hoàn trả thành công", toDetail(sr)));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        SupplierReturn sr = supplierReturnService.confirm(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Xác nhận phiếu hoàn trả thành công", toDetail(sr)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        SupplierReturn sr = supplierReturnService.submit(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Phiếu hoàn trả đã được gửi duyệt", toDetail(sr)));
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ship(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        SupplierReturn sr = supplierReturnService.ship(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Đã xuất kho giao nhà cung cấp", toDetail(sr)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        SupplierReturn sr = supplierReturnService.approve(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Đã duyệt phiếu hoàn trả", toDetail(sr)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(
            @PathVariable UUID id,
            @RequestBody RejectRequest body,
            @AuthenticationPrincipal UserPrincipal principal) {

        SupplierReturn sr = supplierReturnService.reject(id, principal.getId(), body.reason());
        return ResponseEntity.ok(ApiResponse.ok("Đã từ chối phiếu hoàn trả", toDetail(sr)));
    }

    @PostMapping("/{id}/revise")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revise(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        SupplierReturn sr = supplierReturnService.revise(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Phiếu hoàn trả đã được đặt lại để sửa", toDetail(sr)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        SupplierReturn sr = supplierReturnService.cancel(id);
        return ResponseEntity.ok(ApiResponse.ok("Hủy phiếu hoàn trả thành công", toDetail(sr)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> toSummary(SupplierReturn sr) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", sr.getId());
        m.put("code", sr.getCode());
        m.put("supplierId", sr.getSupplierId());
        m.put("warehouseId", sr.getWarehouseId());
        m.put("totalAmount", sr.getTotalAmount());
        m.put("status", sr.getStatus().name());
        m.put("createdAt", sr.getCreatedAt() != null ? sr.getCreatedAt().toString() : "");
        m.put("confirmedAt", sr.getConfirmedAt() != null ? sr.getConfirmedAt().toString() : "");
        m.put("rejectionReason", sr.getRejectionReason() != null ? sr.getRejectionReason() : "");
        m.put("note", sr.getNote() != null ? sr.getNote() : "");
        return m;
    }

    private Map<String, Object> toDetail(SupplierReturn sr) {
        List<Map<String, Object>> items = sr.getItems().stream().map(i -> Map.<String, Object>of(
                "id", i.getId(),
                "productId", i.getProductId(),
                "quantity", i.getQuantity(),
                "unitPrice", i.getUnitPrice(),
                "subtotal", i.getSubtotal(),
                "returnReason", i.getReturnReason() != null ? i.getReturnReason() : ""
        )).toList();

        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("id", sr.getId());
        detail.put("code", sr.getCode());
        detail.put("supplierId", sr.getSupplierId());
        detail.put("purchaseOrderId", sr.getPurchaseOrderId() != null ? sr.getPurchaseOrderId() : "");
        detail.put("warehouseId", sr.getWarehouseId());
        detail.put("totalAmount", sr.getTotalAmount());
        detail.put("status", sr.getStatus().name());
        detail.put("note", sr.getNote() != null ? sr.getNote() : "");
        detail.put("createdAt", sr.getCreatedAt() != null ? sr.getCreatedAt().toString() : "");
        detail.put("submittedBy", sr.getSubmittedBy() != null ? sr.getSubmittedBy().toString() : "");
        detail.put("submittedAt", sr.getSubmittedAt() != null ? sr.getSubmittedAt().toString() : "");
        detail.put("approvedBy", sr.getApprovedBy() != null ? sr.getApprovedBy().toString() : "");
        detail.put("approvedAt", sr.getApprovedAt() != null ? sr.getApprovedAt().toString() : "");
        detail.put("rejectedBy", sr.getRejectedBy() != null ? sr.getRejectedBy().toString() : "");
        detail.put("rejectedAt", sr.getRejectedAt() != null ? sr.getRejectedAt().toString() : "");
        detail.put("rejectionReason", sr.getRejectionReason() != null ? sr.getRejectionReason() : "");
        detail.put("shippedBy", sr.getShippedBy() != null ? sr.getShippedBy().toString() : "");
        detail.put("shippedAt", sr.getShippedAt() != null ? sr.getShippedAt().toString() : "");
        detail.put("confirmedAt", sr.getConfirmedAt() != null ? sr.getConfirmedAt().toString() : "");
        detail.put("confirmedBy", sr.getConfirmedBy() != null ? sr.getConfirmedBy().toString() : "");
        detail.put("items", items);
        return detail;
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    public record RejectRequest(String reason) {}

    public record CreateRequest(
            UUID supplierId,
            UUID warehouseId,
            UUID purchaseOrderId,
            String note,
            List<ItemDto> items) {}

    public record ItemDto(
            UUID productId,
            int quantity,
            BigDecimal unitPrice,
            String returnReason) {}
}
