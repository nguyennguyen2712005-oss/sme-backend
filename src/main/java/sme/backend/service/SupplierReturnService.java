package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierReturnService {

    private final SupplierReturnRepository supplierReturnRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final SupplierDebtRepository supplierDebtRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // ── TẠO PHIẾU HOÀN TRẢ (DRAFT) ────────────────────────────────────────
    @Transactional
    public SupplierReturn create(UUID supplierId, UUID warehouseId, UUID purchaseOrderId,
                                 String note, List<ItemRequest> items, UUID createdBy) {

        supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));

        String code = "SR-" + System.currentTimeMillis();

        SupplierReturn sr = SupplierReturn.builder()
                .code(code)
                .supplierId(supplierId)
                .warehouseId(warehouseId)
                .purchaseOrderId(purchaseOrderId)
                .note(note)
                .status(SupplierReturn.ReturnStatus.DRAFT)
                .build();

        for (ItemRequest req : items) {
            SupplierReturnItem item = SupplierReturnItem.builder()
                    .productId(req.productId())
                    .quantity(req.quantity())
                    .unitPrice(req.unitPrice())
                    .subtotal(req.unitPrice().multiply(BigDecimal.valueOf(req.quantity())))
                    .returnReason(req.returnReason())
                    .build();
            sr.addItem(item);
        }
        sr.recalculateTotal();

        SupplierReturn saved = supplierReturnRepository.save(sr);
        log.info("Tạo phiếu hoàn trả NCC {} cho NCC {}", saved.getCode(), supplierId);
        return saved;
    }

    // ── GỬI DUYỆT (DRAFT → PENDING_APPROVAL) ──────────────────────────────
    @Transactional
    public SupplierReturn submit(UUID returnId, UUID submittedBy) {
        SupplierReturn sr = supplierReturnRepository.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", returnId));

        if (sr.getStatus() != SupplierReturn.ReturnStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể gửi duyệt phiếu ở trạng thái DRAFT.");
        }
        if (sr.getItems() == null || sr.getItems().isEmpty()) {
            throw new BusinessException("EMPTY_ITEMS", "Phiếu hoàn trả phải có ít nhất một sản phẩm.");
        }

        sr.setStatus(SupplierReturn.ReturnStatus.PENDING_APPROVAL);
        sr.setSubmittedBy(submittedBy);
        sr.setSubmittedAt(Instant.now());
        SupplierReturn saved = supplierReturnRepository.save(sr);
        notificationService.notifySupplierReturnPendingApproval(saved);
        log.info("Phiếu hoàn trả {} đã gửi duyệt", saved.getCode());
        return saved;
    }

    // ── DUYỆT (PENDING_APPROVAL → APPROVED) ─────────────────────────────
    @Transactional
    public SupplierReturn approve(UUID returnId, UUID approvedBy) {
        SupplierReturn sr = supplierReturnRepository.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", returnId));

        if (sr.getStatus() != SupplierReturn.ReturnStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể duyệt phiếu ở trạng thái PENDING_APPROVAL.");
        }
        if (approvedBy.equals(sr.getSubmittedBy())) {
            throw new BusinessException("SELF_APPROVAL", "Không thể tự duyệt phiếu của chính mình.");
        }

        sr.setStatus(SupplierReturn.ReturnStatus.APPROVED);
        sr.setApprovedBy(approvedBy);
        sr.setApprovedAt(Instant.now());
        SupplierReturn saved = supplierReturnRepository.save(sr);
        notificationService.notifySupplierReturnApproved(saved);
        log.info("Phiếu hoàn trả {} đã được duyệt bởi {}", saved.getCode(), approvedBy);
        return saved;
    }

    // ── TỪ CHỐI (PENDING_APPROVAL → REJECTED) ───────────────────────────
    @Transactional
    public SupplierReturn reject(UUID returnId, UUID rejectedBy, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Phải nhập lý do từ chối.");
        }
        SupplierReturn sr = supplierReturnRepository.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", returnId));

        if (sr.getStatus() != SupplierReturn.ReturnStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể từ chối phiếu ở trạng thái PENDING_APPROVAL.");
        }

        sr.setStatus(SupplierReturn.ReturnStatus.REJECTED);
        sr.setRejectedBy(rejectedBy);
        sr.setRejectedAt(Instant.now());
        sr.setRejectionReason(reason);
        SupplierReturn saved = supplierReturnRepository.save(sr);
        notificationService.notifySupplierReturnRejected(saved);
        log.info("Phiếu hoàn trả {} bị từ chối: {}", saved.getCode(), reason);
        return saved;
    }

    // ── SỬA LẠI (REJECTED → DRAFT) ──────────────────────────────────────
    @Transactional
    public SupplierReturn revise(UUID returnId, UUID requestedBy) {
        SupplierReturn sr = supplierReturnRepository.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", returnId));

        if (sr.getStatus() != SupplierReturn.ReturnStatus.REJECTED) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể sửa lại phiếu ở trạng thái REJECTED.");
        }
        if (sr.getSubmittedBy() != null && !requestedBy.equals(sr.getSubmittedBy())) {
            throw new BusinessException("FORBIDDEN", "Chỉ người đã gửi phiếu mới có thể sửa lại.");
        }

        sr.setStatus(SupplierReturn.ReturnStatus.DRAFT);
        sr.setRejectionReason(null);
        sr.setRejectedBy(null);
        sr.setRejectedAt(null);
        sr.setSubmittedBy(null);
        sr.setSubmittedAt(null);
        SupplierReturn saved = supplierReturnRepository.save(sr);
        log.info("Phiếu hoàn trả {} đã được đặt lại DRAFT để sửa", saved.getCode());
        return saved;
    }

    // ── XUẤT KHO GIAO NCC (APPROVED → SHIPPED) ──────────────────────────
    @Transactional
    public SupplierReturn ship(UUID returnId, UUID shippedBy) {
        SupplierReturn sr = supplierReturnRepository.findByIdWithItems(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", returnId));

        if (sr.getStatus() != SupplierReturn.ReturnStatus.APPROVED) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể xuất kho phiếu đã được duyệt (APPROVED).");
        }

        String operator = userRepository.findById(shippedBy)
                .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                .orElse(shippedBy.toString());

        // Xuất kho — trừ tồn kho ngay khi giao hàng cho NCC
        for (SupplierReturnItem item : sr.getItems()) {
            Inventory inv = inventoryRepository
                    .findByProductIdAndWarehouseId(item.getProductId(), sr.getWarehouseId())
                    .orElseThrow(() -> new BusinessException("STOCK_NOT_FOUND",
                            "Không tìm thấy tồn kho sản phẩm " + item.getProductId()));

            if (inv.getAvailableQuantity() < item.getQuantity()) {
                Product p = productRepository.findById(item.getProductId()).orElse(null);
                String pName = p != null ? p.getName() : item.getProductId().toString();
                throw new BusinessException("INSUFFICIENT_STOCK",
                        String.format("Sản phẩm '%s' không đủ hàng để xuất. Khả dụng: %d, Yêu cầu: %d",
                                pName, inv.getAvailableQuantity(), item.getQuantity()));
            }

            int before = inv.getQuantity();
            inv.deductPhysicalQuantity(item.getQuantity());
            inventoryRepository.save(inv);

            inventoryTransactionRepository.save(
                    InventoryTransaction.builder()
                            .inventoryId(inv.getId())
                            .referenceId(sr.getId())
                            .transactionType("RETURN_TO_SUPPLIER")
                            .quantityChange(-item.getQuantity())
                            .quantityBefore(before)
                            .quantityAfter(inv.getQuantity())
                            .createdBy(operator)
                            .build());
        }

        sr.setStatus(SupplierReturn.ReturnStatus.SHIPPED);
        sr.setShippedBy(shippedBy);
        sr.setShippedAt(Instant.now());
        SupplierReturn saved = supplierReturnRepository.save(sr);
        log.info("Phiếu hoàn trả {} đã xuất kho giao NCC", saved.getCode());
        return saved;
    }

    // ── NCC XÁC NHẬN ĐÃ NHẬN HÀNG (SHIPPED → CONFIRMED) ───────────────
    @Transactional
    public SupplierReturn confirm(UUID returnId, UUID confirmedBy) {
        SupplierReturn sr = supplierReturnRepository.findByIdWithItems(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", returnId));

        if (sr.getStatus() != SupplierReturn.ReturnStatus.SHIPPED) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể xác nhận NCC nhận hàng khi phiếu ở trạng thái SHIPPED.");
        }

        // Giảm công nợ NCC theo FIFO
        BigDecimal remainingCredit = sr.getTotalAmount();
        List<SupplierDebt> unpaidDebts = supplierDebtRepository
                .findBySupplierIdAndStatusNot(sr.getSupplierId(), SupplierDebt.DebtStatus.PAID);
        unpaidDebts.sort(java.util.Comparator.comparing(SupplierDebt::getCreatedAt));

        for (SupplierDebt debt : unpaidDebts) {
            if (remainingCredit.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal deductible = debt.getRemainingAmount().min(remainingCredit);
            debt.pay(deductible);
            supplierDebtRepository.save(debt);
            remainingCredit = remainingCredit.subtract(deductible);
        }

        sr.setStatus(SupplierReturn.ReturnStatus.CONFIRMED);
        sr.setConfirmedBy(confirmedBy);
        sr.setConfirmedAt(Instant.now());
        SupplierReturn saved = supplierReturnRepository.save(sr);
        log.info("Phiếu hoàn trả {} — NCC xác nhận nhận hàng — giảm công nợ {}", saved.getCode(), sr.getTotalAmount());
        return saved;
    }

    // ── HỦY PHIẾU ──────────────────────────────────────────────────────────
    @Transactional
    public SupplierReturn cancel(UUID returnId) {
        SupplierReturn sr = supplierReturnRepository.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", returnId));

        if (sr.getStatus() != SupplierReturn.ReturnStatus.DRAFT
                && sr.getStatus() != SupplierReturn.ReturnStatus.REJECTED) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể hủy phiếu ở trạng thái DRAFT hoặc REJECTED.");
        }
        sr.setStatus(SupplierReturn.ReturnStatus.CANCELLED);
        return supplierReturnRepository.save(sr);
    }

    // ── DANH SÁCH ──────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Page<SupplierReturn> search(UUID supplierId, UUID warehouseId,
                                       SupplierReturn.ReturnStatus status, Pageable pageable) {
        return supplierReturnRepository.search(supplierId, warehouseId, status, pageable);
    }

    @Transactional(readOnly = true)
    public SupplierReturn getById(UUID id) {
        return supplierReturnRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierReturn", id));
    }

    public record ItemRequest(UUID productId, int quantity, BigDecimal unitPrice, String returnReason) {}
}
