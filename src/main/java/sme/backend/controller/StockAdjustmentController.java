package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.entity.AdjustmentReasonType;
import sme.backend.entity.StockAdjustment;
import sme.backend.entity.User;
import sme.backend.security.UserPrincipal;
import sme.backend.service.StockAdjustmentService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/stock-adjustments")
@RequiredArgsConstructor
public class StockAdjustmentController {

    private final StockAdjustmentService adjustmentService;

    /** Chỉ Manager kho mới được tạo phiếu kiểm kê */
    @PostMapping
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<StockAdjustment>> create(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID warehouseId = UUID.fromString((String) body.get("warehouseId"));
        String note = (String) body.get("note");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) body.get("items");
        List<StockAdjustmentService.AdjustItemRequest> items = rawItems.stream()
                .map(i -> {
                    String reasonTypeStr = (String) i.get("reasonType");
                    AdjustmentReasonType reasonType = null;
                    if (reasonTypeStr != null && !reasonTypeStr.isBlank()) {
                        try { reasonType = AdjustmentReasonType.valueOf(reasonTypeStr.toUpperCase()); }
                        catch (IllegalArgumentException ignored) {}
                    }
                    return new StockAdjustmentService.AdjustItemRequest(
                            UUID.fromString((String) i.get("productId")),
                            Integer.parseInt(i.get("systemQty").toString()),
                            Integer.parseInt(i.get("actualQty").toString()),
                            (String) i.get("reason"),
                            reasonType
                    );
                }).toList();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(adjustmentService.create(
                        warehouseId, items, note, principal.getId())));
    }

    /** Chỉ người tạo phiếu mới được gửi duyệt (kiểm tra trong service) */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<StockAdjustment>> submit(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Gửi duyệt phiếu kiểm kê thành công",
                adjustmentService.submit(id, principal.getId())));
    }

    /** Chỉ Admin mới được duyệt phiếu kiểm kê */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StockAdjustment>> approve(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Duyệt phiếu kiểm kê thành công",
                adjustmentService.approve(id, principal.getId())));
    }

    /** Chỉ Admin mới được từ chối phiếu kiểm kê */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StockAdjustment>> reject(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        return ResponseEntity.ok(ApiResponse.ok("Từ chối phiếu kiểm kê thành công",
                adjustmentService.reject(id, principal.getId(), reason)));
    }

    /** Hủy phiếu — bắt buộc nhập lý do */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<StockAdjustment>> cancel(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        return ResponseEntity.ok(ApiResponse.ok("Hủy phiếu kiểm kê thành công",
                adjustmentService.cancel(id, reason)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<StockAdjustment>>> getAll(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID wid = principal.getRole() == User.UserRole.ROLE_ADMIN ? warehouseId : principal.getWarehouseId();
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.of(
                adjustmentService.search(wid, status, keyword,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<StockAdjustment>> getOne(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(adjustmentService.getById(id)));
    }
}
