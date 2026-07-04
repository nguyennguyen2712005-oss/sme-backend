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
import sme.backend.dto.request.CreatePurchaseOrderRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.entity.PurchaseOrder;
import sme.backend.entity.User;
import sme.backend.security.UserPrincipal;
import sme.backend.service.PurchaseService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/purchase-orders")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> create(
            @Valid @RequestBody CreatePurchaseOrderRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(purchaseService.createPurchaseOrder(
                        req, principal.getId(), principal.getRole().name())));
    }

    /** Chỉnh sửa phiếu nháp (chỉ DRAFT) */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> update(
            @PathVariable UUID id,
            @Valid @RequestBody CreatePurchaseOrderRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Cập nhật phiếu nhập thành công",
                purchaseService.updateDraft(id, req, principal.getId())));
    }

    /** Chỉ người tạo phiếu mới được gửi duyệt (kiểm tra trong service) */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> submit(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Gửi duyệt phiếu nhập thành công",
                purchaseService.submitForApproval(id, principal.getId())));
    }

    /** Duyệt chéo — canApprove() kiểm tra trong service */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Duyệt phiếu nhập kho thành công",
                purchaseService.approvePurchaseOrder(id, principal.getId())));
    }

    /** Từ chối — canApprove() kiểm tra trong service */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        return ResponseEntity.ok(ApiResponse.ok("Từ chối phiếu nhập kho thành công",
                purchaseService.rejectPurchaseOrder(id, principal.getId(), reason)));
    }

    /** Nhận hàng — chỉ Manager kho (Admin không hiện diện tại kho) */
    @PostMapping("/{id}/receive")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> receive(
            @PathVariable UUID id,
            @RequestBody List<PurchaseService.ReceiveItemRequest> receivedItems,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Nhận hàng thành công",
                purchaseService.receivePurchaseOrder(id, receivedItems, principal.getId())));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> cancel(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        return ResponseEntity.ok(ApiResponse.ok("Hủy phiếu nhập thành công",
                purchaseService.cancelPurchaseOrder(id, reason)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<PurchaseOrder>>> getAll(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID wid = principal.getRole() == User.UserRole.ROLE_ADMIN ? warehouseId : principal.getWarehouseId();

        PurchaseOrder.PurchaseStatus poStatus = null;
        if (status != null && !status.isBlank() && !status.equals("ALL")) {
            try { poStatus = PurchaseOrder.PurchaseStatus.valueOf(status.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(
                purchaseService.searchOrders(wid, keyword, poStatus,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))))));
    }

    @GetMapping("/supplier/{supplierId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<PurchaseOrder>>> getBySupplier(
            @PathVariable UUID supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(
                purchaseService.getBySupplier(supplierId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PurchaseOrder>> getOne(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(purchaseService.getById(id)));
    }
}
