package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreatePurchaseOrderRequest;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;
import sme.backend.util.ApprovalUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierDebtRepository supplierDebtRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;

    // ─── TẠO PHIẾU (DRAFT) ──────────────────────────────────────────────────
    @Transactional
    public PurchaseOrder createPurchaseOrder(CreatePurchaseOrderRequest req,
                                              UUID createdBy, String creatorRole) {
        supplierRepository.findById(req.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", req.getSupplierId()));

        PurchaseOrder po = PurchaseOrder.builder()
                .code(generatePOCode())
                .supplierId(req.getSupplierId())
                .warehouseId(req.getWarehouseId())
                .createdByUserId(createdBy)
                .creatorRole(creatorRole)
                .note(req.getNote())
                .status(PurchaseOrder.PurchaseStatus.DRAFT)
                .build();

        for (CreatePurchaseOrderRequest.PurchaseItemRequest itemReq : req.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Sản phẩm không tồn tại (ID: " + itemReq.getProductId() + ")"));
            PurchaseItem item = PurchaseItem.builder()
                    .productId(product.getId())
                    .quantity(itemReq.getQuantity() != null ? itemReq.getQuantity() : 0)
                    .importPrice(itemReq.getImportPrice() != null ? itemReq.getImportPrice() : BigDecimal.ZERO)
                    .build();
            po.addItem(item);
        }
        po.recalculateTotal();
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        log.info("Purchase order created as DRAFT: {} by {} ({})", saved.getCode(), createdBy, creatorRole);
        return saved;
    }

    // ─── CHỈNH SỬA PHIẾU NHÁP ──────────────────────────────────────────────────
    @Transactional
    public PurchaseOrder updateDraft(UUID poId, CreatePurchaseOrderRequest req, UUID updatedBy) {
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId));
        boolean isDraft = po.getStatus() == PurchaseOrder.PurchaseStatus.DRAFT;
        boolean isRejected = po.getStatus() == PurchaseOrder.PurchaseStatus.REJECTED;
        if (!isDraft && !isRejected) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể chỉnh sửa phiếu ở trạng thái DRAFT hoặc BỊ TỪ CHỐI.");
        }
        // Phiếu bị từ chối → reset về DRAFT để có thể gửi duyệt lại
        if (isRejected) {
            po.setStatus(PurchaseOrder.PurchaseStatus.DRAFT);
            po.setRejectedBy(null);
            po.setRejectedAt(null);
            po.setRejectionReason(null);
        }
        if (req.getNote() != null) po.setNote(req.getNote());
        po.getItems().clear();
        for (CreatePurchaseOrderRequest.PurchaseItemRequest itemReq : req.getItems()) {
            productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm", itemReq.getProductId()));
            PurchaseItem item = PurchaseItem.builder()
                    .productId(itemReq.getProductId())
                    .quantity(itemReq.getQuantity() != null ? itemReq.getQuantity() : 0)
                    .importPrice(itemReq.getImportPrice() != null ? itemReq.getImportPrice() : BigDecimal.ZERO)
                    .build();
            po.addItem(item);
        }
        po.recalculateTotal();
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        log.info("Purchase order DRAFT {} updated by {}", saved.getCode(), updatedBy);
        return saved;
    }

    // ─── GỬI DUYỆT (DRAFT → PENDING_APPROVAL) ───────────────────────────────
    @Transactional
    public PurchaseOrder submitForApproval(UUID poId, UUID submittedBy) {
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId));

        if (po.getStatus() != PurchaseOrder.PurchaseStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể gửi duyệt phiếu ở trạng thái DRAFT.");
        }
        if (!po.getCreatedByUserId().equals(submittedBy)) {
            throw new BusinessException("FORBIDDEN", "Chỉ người tạo phiếu mới có thể gửi duyệt.");
        }
        if (po.getItems() == null || po.getItems().isEmpty()) {
            throw new BusinessException("EMPTY_ITEMS", "Phiếu nhập phải có ít nhất một sản phẩm.");
        }

        po.setStatus(PurchaseOrder.PurchaseStatus.PENDING_APPROVAL);
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        notificationService.notifyPurchasePendingApproval(saved);
        log.info("Purchase order submitted for approval: {}", saved.getCode());
        return saved;
    }

    // ─── DUYỆT (PENDING_APPROVAL → APPROVED) — cross-approval ───────────────
    @Transactional
    public PurchaseOrder approvePurchaseOrder(UUID poId, UUID approvedBy) {
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId));

        if (po.getStatus() != PurchaseOrder.PurchaseStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể duyệt phiếu ở trạng thái PENDING_APPROVAL.");
        }
        if (po.getWarehouseId() == null) {
            throw new BusinessException("MISSING_WAREHOUSE", "Phiếu nhập kho chưa được gán chi nhánh.");
        }

        User approver = userRepository.findById(approvedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", approvedBy));
        if (!ApprovalUtils.canApprove(approver, po.getCreatedByUserId(),
                po.getCreatorRole(), po.getWarehouseId())) {
            throw new BusinessException("FORBIDDEN",
                    "Bạn không có quyền duyệt phiếu này (vi phạm quy tắc duyệt chéo).");
        }

        po.setStatus(PurchaseOrder.PurchaseStatus.APPROVED);
        po.setApprovedBy(approvedBy);
        po.setApprovedAt(Instant.now());
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        notificationService.notifyPurchaseApproved(saved);
        log.info("Purchase order approved: {} by {}", saved.getCode(), approvedBy);
        return saved;
    }

    // ─── TỪ CHỐI (PENDING_APPROVAL → REJECTED) — cross-approval ─────────────
    @Transactional
    public PurchaseOrder rejectPurchaseOrder(UUID poId, UUID rejectedBy, String reason) {
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId));

        if (po.getStatus() != PurchaseOrder.PurchaseStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể từ chối phiếu ở trạng thái PENDING_APPROVAL.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do từ chối.");
        }

        User approver = userRepository.findById(rejectedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", rejectedBy));
        if (!ApprovalUtils.canApprove(approver, po.getCreatedByUserId(),
                po.getCreatorRole(), po.getWarehouseId())) {
            throw new BusinessException("FORBIDDEN",
                    "Bạn không có quyền từ chối phiếu này (vi phạm quy tắc duyệt chéo).");
        }

        po.setStatus(PurchaseOrder.PurchaseStatus.REJECTED);
        po.setRejectedBy(rejectedBy);
        po.setRejectedAt(Instant.now());
        po.setRejectionReason(reason);
        PurchaseOrder saved = purchaseOrderRepository.save(po);
        notificationService.notifyPurchaseRejected(saved);
        log.info("Purchase order rejected: {} - reason: {}", saved.getCode(), reason);
        return saved;
    }

    // ─── NHẬN HÀNG (APPROVED/PARTIAL_RECEIVED → PARTIAL_RECEIVED/COMPLETED) ──
    @Transactional
    public PurchaseOrder receivePurchaseOrder(UUID poId, List<ReceiveItemRequest> receivedItems,
                                               UUID receivedBy) {
        PurchaseOrder po = purchaseOrderRepository.findByIdWithItems(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId));

        if (po.getStatus() != PurchaseOrder.PurchaseStatus.APPROVED
                && po.getStatus() != PurchaseOrder.PurchaseStatus.PARTIAL_RECEIVED) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể nhận hàng phiếu ở trạng thái APPROVED hoặc PARTIAL_RECEIVED.");
        }

        User receiver = userRepository.findById(receivedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", receivedBy));
        if (receiver.getRole() == User.UserRole.ROLE_MANAGER
                && po.getWarehouseId() != null
                && !po.getWarehouseId().equals(receiver.getWarehouseId())) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền nhận hàng cho kho này.");
        }

        String operator = receiver.getFullName() != null ? receiver.getFullName() : receiver.getUsername();

        // Lưu trạng thái receivedQty trước khi xử lý để tính công nợ đợt này
        java.util.Map<UUID, Integer> prevQtyMap = new java.util.HashMap<>();
        for (PurchaseItem item : po.getItems()) {
            prevQtyMap.put(item.getId(), item.getReceivedQty() != null ? item.getReceivedQty() : 0);
        }

        try {
            for (PurchaseItem item : po.getItems()) {
                int alreadyReceived = prevQtyMap.getOrDefault(item.getId(), 0);
                int remaining = item.getQuantity() - alreadyReceived;

                ReceiveItemRequest req = receivedItems.stream()
                        .filter(r -> r.productId().equals(item.getProductId()))
                        .findFirst()
                        .orElse(null);

                // Nếu không có trong request → nhận hết số còn lại (default behavior)
                int batchQty = (req != null) ? req.receivedQty() : remaining;
                String receiveNote = (req != null) ? req.receiveNote() : null;

                if (batchQty < 0 || batchQty > remaining) {
                    throw new BusinessException("INVALID_QTY",
                            String.format("Số lượng nhận không hợp lệ cho sản phẩm %s. Còn cần nhận: %d",
                                    item.getProductId(), remaining));
                }

                item.setReceivedQty(alreadyReceived + batchQty);
                if (receiveNote != null) item.setReceiveNote(receiveNote);

                if (batchQty > 0) {
                    BigDecimal importPrice = item.getImportPrice() != null
                            ? item.getImportPrice() : BigDecimal.ZERO;
                    inventoryService.importStock(item.getProductId(), po.getWarehouseId(),
                            batchQty, importPrice, po.getId(), operator);
                }
            }

            // Kiểm tra đã nhận đủ tất cả chưa
            boolean allComplete = po.getItems().stream()
                    .allMatch(i -> i.getReceivedQty() != null && i.getReceivedQty() >= i.getQuantity());

            PurchaseOrder.PurchaseStatus newStatus = allComplete
                    ? PurchaseOrder.PurchaseStatus.COMPLETED
                    : PurchaseOrder.PurchaseStatus.PARTIAL_RECEIVED;

            po.setStatus(newStatus);
            po.setReceivedBy(receivedBy);
            po.setReceivedAt(Instant.now());
            po = purchaseOrderRepository.save(po);

            // Tạo công nợ cho đợt nhận này (chỉ phần thực nhận trong đợt)
            Supplier supplier = supplierRepository.findById(po.getSupplierId()).orElse(null);
            int paymentTerms = (supplier != null && supplier.getPaymentTerms() != null)
                    ? supplier.getPaymentTerms() : 30;

            BigDecimal batchTotal = BigDecimal.ZERO;
            for (PurchaseItem item : po.getItems()) {
                int prev = prevQtyMap.getOrDefault(item.getId(), 0);
                int batchQty = (item.getReceivedQty() != null ? item.getReceivedQty() : 0) - prev;
                if (batchQty > 0) {
                    BigDecimal price = item.getImportPrice() != null ? item.getImportPrice() : BigDecimal.ZERO;
                    batchTotal = batchTotal.add(price.multiply(BigDecimal.valueOf(batchQty)));
                }
            }

            if (batchTotal.compareTo(BigDecimal.ZERO) > 0) {
                SupplierDebt debt = SupplierDebt.builder()
                        .supplierId(po.getSupplierId())
                        .purchaseOrderId(po.getId())
                        .totalDebt(batchTotal)
                        .paidAmount(BigDecimal.ZERO)
                        .status(SupplierDebt.DebtStatus.UNPAID)
                        .dueDate(LocalDate.now().plusDays(paymentTerms))
                        .build();
                supplierDebtRepository.save(debt);
            }

            if (newStatus == PurchaseOrder.PurchaseStatus.COMPLETED) {
                notificationService.notifyImportSuccess(po, po.getWarehouseId());
                log.info("Purchase order fully received and completed: {}", po.getCode());
            } else {
                log.info("Purchase order partially received ({}): {} — batch total {}",
                        newStatus, po.getCode(), batchTotal);
            }
            return po;

        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Error receiving purchase order: ", e);
            throw new BusinessException("RECEIVE_FAILED", "Nhận hàng thất bại: " + e.getMessage());
        }
    }

    // ─── HỦY — bắt buộc có lý do ─────────────────────────────────────────────
    @Transactional
    public PurchaseOrder cancelPurchaseOrder(UUID poId, String reason) {
        PurchaseOrder po = purchaseOrderRepository.findById(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", poId));

        if (po.getStatus() == PurchaseOrder.PurchaseStatus.COMPLETED
                || po.getStatus() == PurchaseOrder.PurchaseStatus.APPROVED
                || po.getStatus() == PurchaseOrder.PurchaseStatus.PARTIAL_RECEIVED) {
            throw new BusinessException("CANNOT_CANCEL",
                    "Không thể hủy phiếu đã duyệt, đang nhận hoặc đã hoàn thành.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do hủy.");
        }

        po.setStatus(PurchaseOrder.PurchaseStatus.CANCELLED);
        po.setCancelReason(reason);
        return purchaseOrderRepository.save(po);
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrder> searchOrders(UUID warehouseId, String keyword,
            PurchaseOrder.PurchaseStatus status, Pageable pageable) {
        return purchaseOrderRepository.searchPurchaseOrders(warehouseId, status, keyword, pageable);
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrder> getBySupplier(UUID supplierId, Pageable pageable) {
        return purchaseOrderRepository.findBySupplierIdOrderByCreatedAtDesc(supplierId, pageable);
    }

    @Transactional(readOnly = true)
    public PurchaseOrder getById(UUID id) {
        return purchaseOrderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", id));
    }

    private String generatePOCode() {
        return "PO-" + System.currentTimeMillis();
    }

    public record ReceiveItemRequest(UUID productId, int receivedQty, String receiveNote) {}
}
