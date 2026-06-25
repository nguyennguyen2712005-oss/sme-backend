package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.AdjustInventoryRequest;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.InventoryRepository;
import sme.backend.repository.StockAdjustmentRepository;
import sme.backend.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockAdjustmentService {

    private final StockAdjustmentRepository adjustmentRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public record AdjustItemRequest(
            UUID productId,
            int systemQty,
            int actualQty,
            String reason,
            AdjustmentReasonType reasonType) {}

    // ─── TẠO PHIẾU KIỂM KÊ (DRAFT) — chỉ Manager ────────────────────────────
    @Transactional
    public StockAdjustment create(UUID warehouseId, List<AdjustItemRequest> items,
                                  String note, UUID createdBy) {
        User creator = userRepository.findById(createdBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", createdBy));
        if (creator.getRole() == User.UserRole.ROLE_ADMIN) {
            throw new BusinessException("FORBIDDEN",
                    "Admin không có quyền tạo phiếu kiểm kê (chỉ Quản lý kho mới được tạo).");
        }

        StockAdjustment adj = StockAdjustment.builder()
                .code("ADJ-" + System.currentTimeMillis())
                .warehouseId(warehouseId)
                .createdByUser(createdBy)
                .note(note)
                .status(StockAdjustment.AdjustmentStatus.DRAFT)
                .build();

        for (AdjustItemRequest req : items) {
            int diff = req.actualQty() - req.systemQty();
            adj.addItem(StockAdjustmentItem.builder()
                    .productId(req.productId())
                    .systemQty(req.systemQty())
                    .actualQty(req.actualQty())
                    .diffQty(diff)
                    .reason(req.reason())
                    .reasonType(req.reasonType())
                    .build());
        }

        StockAdjustment saved = adjustmentRepository.save(adj);
        log.info("Stock adjustment created: {}", saved.getCode());
        return saved;
    }

    // ─── GỬI DUYỆT (DRAFT → PENDING_APPROVAL) ───────────────────────────────
    @Transactional
    public StockAdjustment submit(UUID adjId, UUID submittedBy) {
        StockAdjustment adj = adjustmentRepository.findByIdWithItems(adjId)
                .orElseThrow(() -> new ResourceNotFoundException("StockAdjustment", adjId));

        if (adj.getStatus() != StockAdjustment.AdjustmentStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS", "Chỉ gửi duyệt phiếu ở trạng thái DRAFT.");
        }
        if (!adj.getCreatedByUser().equals(submittedBy)) {
            throw new BusinessException("FORBIDDEN", "Chỉ người tạo phiếu mới có thể gửi duyệt.");
        }
        if (adj.getItems() == null || adj.getItems().isEmpty()) {
            throw new BusinessException("EMPTY_ITEMS", "Phiếu kiểm kê phải có ít nhất một sản phẩm.");
        }

        adj.setStatus(StockAdjustment.AdjustmentStatus.PENDING_APPROVAL);
        adj.setSubmittedAt(Instant.now());
        StockAdjustment saved = adjustmentRepository.save(adj);
        notificationService.notifyAdjustmentPendingApproval(saved);
        return saved;
    }

    // ─── DUYỆT (PENDING_APPROVAL → APPROVED) — chỉ Admin ────────────────────
    @Transactional
    public StockAdjustment approve(UUID adjId, UUID approvedBy) {
        StockAdjustment adj = adjustmentRepository.findByIdWithItems(adjId)
                .orElseThrow(() -> new ResourceNotFoundException("StockAdjustment", adjId));

        if (adj.getStatus() != StockAdjustment.AdjustmentStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ duyệt phiếu ở trạng thái PENDING_APPROVAL.");
        }

        User approver = userRepository.findById(approvedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", approvedBy));
        if (approver.getRole() != User.UserRole.ROLE_ADMIN) {
            throw new BusinessException("FORBIDDEN", "Chỉ Admin mới có quyền duyệt phiếu kiểm kê.");
        }

        for (StockAdjustmentItem item : adj.getItems()) {
            if (item.getDiffQty() != 0) {
                AdjustInventoryRequest req = new AdjustInventoryRequest();
                req.setProductId(item.getProductId());
                req.setWarehouseId(adj.getWarehouseId());
                req.setActualQuantity(item.getActualQty());
                req.setReason("Kiểm kê " + adj.getCode()
                        + (item.getReason() != null ? ": " + item.getReason() : ""));
                inventoryService.adjustInventory(req, adj.getId(), approvedBy.toString());
            }
        }

        adj.setStatus(StockAdjustment.AdjustmentStatus.APPROVED);
        adj.setApprovedBy(approvedBy);
        adj.setApprovedAt(Instant.now());
        StockAdjustment saved = adjustmentRepository.save(adj);
        log.info("Stock adjustment approved: {}", saved.getCode());
        return saved;
    }

    // ─── TỪ CHỐI (PENDING_APPROVAL → REJECTED) — chỉ Admin ──────────────────
    @Transactional
    public StockAdjustment reject(UUID adjId, UUID rejectedBy, String reason) {
        StockAdjustment adj = adjustmentRepository.findByIdWithItems(adjId)
                .orElseThrow(() -> new ResourceNotFoundException("StockAdjustment", adjId));

        if (adj.getStatus() != StockAdjustment.AdjustmentStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ từ chối phiếu ở trạng thái PENDING_APPROVAL.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do từ chối.");
        }

        adj.setStatus(StockAdjustment.AdjustmentStatus.REJECTED);
        adj.setRejectedBy(rejectedBy);
        adj.setRejectedAt(Instant.now());
        adj.setRejectionReason(reason);
        return adjustmentRepository.save(adj);
    }

    // ─── HỦY — bắt buộc có lý do ─────────────────────────────────────────────
    @Transactional
    public StockAdjustment cancel(UUID adjId, String reason) {
        StockAdjustment adj = adjustmentRepository.findById(adjId)
                .orElseThrow(() -> new ResourceNotFoundException("StockAdjustment", adjId));

        if (adj.getStatus() == StockAdjustment.AdjustmentStatus.APPROVED) {
            throw new BusinessException("CANNOT_CANCEL", "Không thể hủy phiếu đã duyệt.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do hủy.");
        }

        adj.setStatus(StockAdjustment.AdjustmentStatus.CANCELLED);
        adj.setCancelReason(reason);
        return adjustmentRepository.save(adj);
    }

    @Transactional(readOnly = true)
    public Page<StockAdjustment> search(UUID warehouseId, String statusStr,
                                        String keyword, Pageable pageable) {
        StockAdjustment.AdjustmentStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try { status = StockAdjustment.AdjustmentStatus.valueOf(statusStr.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        return adjustmentRepository.search(warehouseId, status, keyword, pageable);
    }

    @Transactional(readOnly = true)
    public StockAdjustment getById(UUID id) {
        return adjustmentRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("StockAdjustment", id));
    }
}
