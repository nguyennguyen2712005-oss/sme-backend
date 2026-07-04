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
import sme.backend.security.UserPrincipal;
import sme.backend.util.ApprovalUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private final InternalTransferRepository transferRepository;
    private final InventoryService inventoryService;
    private final InventoryRepository inventoryRepository;

    private final NotificationService notificationService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    // ─── TẠO PHIẾU (DRAFT) ──────────────────────────────────────────────────
    @Transactional
    public InternalTransfer createTransfer(UUID fromWarehouseId, UUID toWarehouseId,
                                           List<TransferItemRequest> items,
                                           String note, UUID createdBy, String creatorRole) {
        if (fromWarehouseId.equals(toWarehouseId)) {
            throw new BusinessException("SAME_WAREHOUSE", "Kho nguồn và kho đích không thể giống nhau");
        }

        for (TransferItemRequest item : items) {
            Inventory inv = inventoryRepository
                    .findByProductIdAndWarehouseId(item.productId(), fromWarehouseId)
                    .orElseThrow(() -> new BusinessException("NO_INVENTORY",
                            "Không tìm thấy tồn kho sản phẩm: " + item.productId()));
            if (inv.getAvailableQuantity() < item.quantity()) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Không đủ hàng để chuyển. Khả dụng: " + inv.getAvailableQuantity());
            }
        }

        InternalTransfer transfer = InternalTransfer.builder()
                .code("TRF-" + System.currentTimeMillis())
                .fromWarehouseId(fromWarehouseId)
                .toWarehouseId(toWarehouseId)
                .createdByUserId(createdBy)
                .creatorRole(creatorRole)
                .status(InternalTransfer.TransferStatus.DRAFT)
                .note(note)
                .build();

        items.forEach(i -> transfer.addItem(
                TransferItem.builder().productId(i.productId()).quantity(i.quantity()).build()
        ));

        return transferRepository.save(transfer);
    }

    // ─── CẬP NHẬT (chỉ khi DRAFT) ───────────────────────────────────────────
    @Transactional
    public InternalTransfer updateTransfer(UUID transferId, UUID toWarehouseId,
                                           List<TransferItemRequest> items,
                                           String note, UUID updatedBy) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        if (transfer.getStatus() != InternalTransfer.TransferStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể sửa phiếu ở trạng thái DRAFT");
        }
        if (transfer.getReferenceOrderId() != null) {
            throw new BusinessException("AUTO_TRANSFER_LOCKED",
                    "Không thể sửa phiếu chuyển kho gom hàng do hệ thống tự tạo.");
        }
        if (transfer.getFromWarehouseId().equals(toWarehouseId)) {
            throw new BusinessException("SAME_WAREHOUSE", "Kho nguồn và kho đích không thể giống nhau");
        }

        for (TransferItemRequest item : items) {
            Inventory inv = inventoryRepository
                    .findByProductIdAndWarehouseId(item.productId(), transfer.getFromWarehouseId())
                    .orElseThrow(() -> new BusinessException("NO_INVENTORY",
                            "Không tìm thấy tồn kho sản phẩm: " + item.productId()));
            if (inv.getAvailableQuantity() < item.quantity()) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Không đủ hàng để chuyển. Khả dụng: " + inv.getAvailableQuantity());
            }
        }

        transfer.setToWarehouseId(toWarehouseId);
        transfer.setNote(note);
        transfer.getItems().clear();
        items.forEach(i -> transfer.addItem(
                TransferItem.builder().productId(i.productId()).quantity(i.quantity()).build()
        ));

        return transferRepository.save(transfer);
    }

    // ─── GỬI DUYỆT (DRAFT → PENDING_APPROVAL) — chỉ người tạo, chỉ Manager ──
    @Transactional
    public InternalTransfer submitForApproval(UUID transferId, UUID submittedBy) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        if (transfer.getStatus() != InternalTransfer.TransferStatus.DRAFT) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể gửi duyệt phiếu ở trạng thái DRAFT.");
        }
        if (transfer.getReferenceOrderId() != null) {
            throw new BusinessException("AUTO_TRANSFER_LOCKED",
                    "Phiếu chuyển kho tự động không cần duyệt.");
        }
        if (!transfer.getCreatedByUserId().equals(submittedBy)) {
            throw new BusinessException("FORBIDDEN", "Chỉ người tạo phiếu mới có thể gửi duyệt.");
        }
        if (transfer.getItems() == null || transfer.getItems().isEmpty()) {
            throw new BusinessException("EMPTY_ITEMS", "Phiếu chuyển phải có ít nhất một sản phẩm.");
        }

        User submitter = userRepository.findById(submittedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", submittedBy));
        if (submitter.getRole() != User.UserRole.ROLE_MANAGER) {
            throw new BusinessException("FORBIDDEN",
                    "Admin không có quyền gửi duyệt phiếu chuyển kho (Admin không hiện diện tại kho).");
        }

        transfer.setStatus(InternalTransfer.TransferStatus.PENDING_APPROVAL);
        InternalTransfer saved = transferRepository.save(transfer);
        notificationService.notifyTransferPendingApproval(saved);
        log.info("Transfer submitted for approval: {}", saved.getCode());
        return saved;
    }

    // ─── DUYỆT (PENDING_APPROVAL → APPROVED) — cross-approval ───────────────
    @Transactional
    public InternalTransfer approveTransfer(UUID transferId, UUID approvedBy) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        if (transfer.getStatus() != InternalTransfer.TransferStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể duyệt phiếu ở trạng thái PENDING_APPROVAL.");
        }

        User approver = userRepository.findById(approvedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", approvedBy));
        if (!ApprovalUtils.canApprove(approver, transfer.getCreatedByUserId(),
                transfer.getCreatorRole(), transfer.getFromWarehouseId())) {
            throw new BusinessException("FORBIDDEN",
                    "Bạn không có quyền duyệt phiếu này (vi phạm quy tắc duyệt chéo).");
        }

        transfer.setStatus(InternalTransfer.TransferStatus.APPROVED);
        transfer.setApprovedBy(approvedBy);
        transfer.setApprovedAt(Instant.now());
        InternalTransfer saved = transferRepository.save(transfer);
        notificationService.notifyTransferApproved(saved);
        log.info("Transfer approved: {} by {}", saved.getCode(), approvedBy);
        return saved;
    }

    // ─── TỪ CHỐI DUYỆT (PENDING_APPROVAL → REJECTED) — cross-approval ────────
    @Transactional
    public InternalTransfer rejectTransfer(UUID transferId, UUID rejectedBy, String reason) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        if (transfer.getStatus() != InternalTransfer.TransferStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể từ chối phiếu ở trạng thái PENDING_APPROVAL.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do từ chối.");
        }

        User approver = userRepository.findById(rejectedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", rejectedBy));
        if (!ApprovalUtils.canApprove(approver, transfer.getCreatedByUserId(),
                transfer.getCreatorRole(), transfer.getFromWarehouseId())) {
            throw new BusinessException("FORBIDDEN",
                    "Bạn không có quyền từ chối phiếu này (vi phạm quy tắc duyệt chéo).");
        }

        transfer.setStatus(InternalTransfer.TransferStatus.REJECTED);
        transfer.setRejectedBy(rejectedBy);
        transfer.setRejectedAt(Instant.now());
        transfer.setRejectionReason(reason);
        InternalTransfer saved = transferRepository.save(transfer);
        notificationService.notifyTransferRejected(saved);
        log.info("Transfer rejected: {} - reason: {}", saved.getCode(), reason);
        return saved;
    }

    // ─── XUẤT KHO (APPROVED → DISPATCHED, hoặc DRAFT → DISPATCHED cho auto) ──
    @Transactional
    public InternalTransfer dispatch(UUID transferId, UUID dispatchedBy) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        boolean isAutoTransfer = transfer.getReferenceOrderId() != null;

        boolean validStatus = isAutoTransfer
                ? transfer.getStatus() == InternalTransfer.TransferStatus.DRAFT
                : transfer.getStatus() == InternalTransfer.TransferStatus.APPROVED;

        if (!validStatus) {
            String expected = isAutoTransfer ? "DRAFT" : "APPROVED";
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể xuất kho phiếu ở trạng thái " + expected + ".");
        }

        User user = userRepository.findById(dispatchedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", dispatchedBy));
        if (user.getRole() == User.UserRole.ROLE_MANAGER
                && !transfer.getFromWarehouseId().equals(user.getWarehouseId())) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền xuất kho này");
        }

        String dispatcherName = user.getFullName() != null ? user.getFullName() : user.getUsername();

        for (TransferItem item : transfer.getItems()) {
            if (isAutoTransfer) {
                inventoryService.releaseReservation(
                        item.getProductId(), transfer.getFromWarehouseId(),
                        item.getQuantity(), transfer.getReferenceOrderId(), dispatcherName
                );
            }

            Inventory inv = inventoryRepository
                    .findByProductAndWarehouseWithLock(item.getProductId(), transfer.getFromWarehouseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Inventory product=" + item.getProductId()));

            if (!isAutoTransfer && inv.getAvailableQuantity() < item.getQuantity()) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Không đủ hàng để chuyển. Khả dụng: " + inv.getAvailableQuantity());
            }

            int before = inv.getQuantity() != null ? inv.getQuantity() : 0;
            inv.dispatchForTransfer(item.getQuantity());
            inv = inventoryRepository.save(inv);

            inventoryService.recordTransaction(inv, transfer.getId(), "TRANSFER_OUT",
                    -item.getQuantity(), before, inv.getQuantity(), dispatcherName,
                    "Xuất luân chuyển kho");
        }

        transfer.setStatus(InternalTransfer.TransferStatus.DISPATCHED);
        transfer.setDispatchedAt(Instant.now());
        transfer.setDispatchedBy(dispatchedBy.toString());
        transfer = transferRepository.save(transfer);

        notificationService.notifyTransferArrived(transfer.getId(), transfer.getToWarehouseId());
        log.info("Transfer dispatched: {}", transfer.getCode());
        return transfer;
    }

    // ─── NHẬN HÀNG (DISPATCHED → RECEIVED / RECEIVED_PARTIAL) ───────────────
    @Transactional
    public InternalTransfer receive(UUID transferId, List<ReceiveItemRequest> receivedItems,
                                    UUID receivedBy) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        if (transfer.getStatus() != InternalTransfer.TransferStatus.DISPATCHED) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể nhận hàng phiếu ở trạng thái DISPATCHED");
        }

        User user = userRepository.findById(receivedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", receivedBy));
        if (user.getRole() == User.UserRole.ROLE_MANAGER
                && !transfer.getToWarehouseId().equals(user.getWarehouseId())) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền nhận hàng cho kho này");
        }

        String receiverName = user.getFullName() != null ? user.getFullName() : user.getUsername();
        boolean isAutoTransfer = transfer.getReferenceOrderId() != null;
        boolean hasDiscrepancy = false;

        // Capture before the loop — transfer is reassigned after the loop, so these must be final
        final String tCode = transfer.getCode();
        final UUID tId = transfer.getId();

        for (TransferItem item : transfer.getItems()) {
            ReceiveItemRequest req = receivedItems.stream()
                    .filter(ri -> ri.productId().equals(item.getProductId()))
                    .findFirst()
                    .orElse(null);

            int actualReceivedQty = (req != null) ? req.receivedQty() : 0;

            if (actualReceivedQty > item.getQuantity() || actualReceivedQty < 0) {
                throw new BusinessException("INVALID_QUANTITY",
                        "Số lượng thực nhận không hợp lệ đối với sản phẩm: " + item.getProductId());
            }

            int discrepancy = item.getQuantity() - actualReceivedQty;
            if (discrepancy > 0) {
                hasDiscrepancy = true;
                String discrepancyReason = (req != null) ? req.discrepancyReason() : null;
                if (discrepancyReason == null || discrepancyReason.isBlank()) {
                    throw new BusinessException("MISSING_REASON",
                            "Vui lòng nhập lý do chênh lệch cho sản phẩm: " + item.getProductId());
                }
                item.setDiscrepancyReason(discrepancyReason);
            }
            item.setDiscrepancyQty(discrepancy);

            if (actualReceivedQty > 0) {
                Inventory destInv = inventoryService.getOrCreate(item.getProductId(),
                        transfer.getToWarehouseId());
                int before = destInv.getQuantity() != null ? destInv.getQuantity() : 0;
                destInv.addQuantity(actualReceivedQty);
                destInv = inventoryRepository.save(destInv);
                inventoryService.recordTransaction(destInv, transfer.getId(), "TRANSFER_IN",
                        actualReceivedQty, before, destInv.getQuantity(), receiverName,
                        "Nhận hàng luân chuyển (Thực nhận: " + actualReceivedQty + "/" + item.getQuantity() + ")");
            }

            // Xóa hàng đang đi đường tại kho nguồn; hoàn lại phần chênh lệch nếu nhận thiếu
            final int discrepancyToReturn = discrepancy;
            final String discrepancyRsnFinal = item.getDiscrepancyReason();
            inventoryRepository.findByProductAndWarehouseWithLock(
                    item.getProductId(), transfer.getFromWarehouseId())
                    .ifPresent(srcInv -> {
                        // Luôn xóa toàn bộ in-transit cho item này
                        srcInv.setInTransit(Math.max(0, srcInv.getInTransit() - item.getQuantity()));

                        if (discrepancyToReturn > 0) {
                            // Hoàn lại số lượng thiếu về tồn kho kho xuất
                            int before = srcInv.getQuantity() != null ? srcInv.getQuantity() : 0;
                            srcInv.addQuantity(discrepancyToReturn);
                            inventoryRepository.save(srcInv);
                            inventoryService.recordTransaction(srcInv, tId, "TRANSFER_RETURN",
                                    discrepancyToReturn, before, srcInv.getQuantity(),
                                    receiverName,
                                    "Hoàn " + discrepancyToReturn + " đơn vị thiếu về kho xuất — phiếu "
                                            + tCode
                                            + (discrepancyRsnFinal != null ? ": " + discrepancyRsnFinal : ""));
                        } else {
                            inventoryRepository.save(srcInv);
                        }
                    });

            item.setReceivedQty(actualReceivedQty);

            if (isAutoTransfer && actualReceivedQty > 0) {
                inventoryService.reserveForOnlineOrder(
                        item.getProductId(), transfer.getToWarehouseId(),
                        actualReceivedQty, transfer.getReferenceOrderId(), receivedBy.toString()
                );
            }
        }

        transfer.setStatus(hasDiscrepancy
                ? InternalTransfer.TransferStatus.RECEIVED_PARTIAL
                : InternalTransfer.TransferStatus.RECEIVED);
        transfer.setReceivedByUserId(receivedBy);
        transfer.setReceivedAt(Instant.now());
        transfer = transferRepository.save(transfer);

        log.info("Transfer {}: {} by {}", transfer.getCode(), transfer.getStatus(), receivedBy);
        if (hasDiscrepancy) {
            notificationService.notifyTransferReceivedPartial(transfer);
        } else {
            notificationService.notifyTransferReceivedFull(transfer);
        }

        if (isAutoTransfer) {
            UUID orderId = transfer.getReferenceOrderId();
            transferRepository.flush();
            List<InternalTransfer> allTransfers = transferRepository.findByReferenceOrderId(orderId);

            boolean allDone = allTransfers.stream()
                    .allMatch(t -> t.getStatus() == InternalTransfer.TransferStatus.RECEIVED
                               || t.getStatus() == InternalTransfer.TransferStatus.RECEIVED_PARTIAL
                               || t.getStatus() == InternalTransfer.TransferStatus.REJECTED_BY_RECEIVER
                               || t.getStatus() == InternalTransfer.TransferStatus.CANCELLED);

            if (allDone) {
                orderRepository.findById(orderId).ifPresent(order -> {
                    if (order.getStatus() == Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
                        order.transitionTo(Order.OrderStatus.PENDING,
                                "Hệ thống tự động: Đã nhận đủ hàng luân chuyển", "SYSTEM");
                        orderRepository.save(order);
                        log.info("Order {} ready after transfer consolidation", order.getCode());
                    }
                });
            }
        }

        return transfer;
    }

    // ─── KHO NHẬP TỪ CHỐI (DISPATCHED → REJECTED_BY_RECEIVER) ───────────────
    @Transactional
    public InternalTransfer rejectReceive(UUID transferId, UUID rejectedBy, String reason) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        if (transfer.getStatus() != InternalTransfer.TransferStatus.DISPATCHED) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể từ chối nhận hàng phiếu ở trạng thái DISPATCHED.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do từ chối nhận hàng.");
        }

        User user = userRepository.findById(rejectedBy)
                .orElseThrow(() -> new ResourceNotFoundException("User", rejectedBy));
        if (user.getRole() == User.UserRole.ROLE_MANAGER
                && !transfer.getToWarehouseId().equals(user.getWarehouseId())) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền từ chối nhận hàng cho kho này.");
        }

        // Hoàn toàn bộ hàng về kho xuất
        for (TransferItem item : transfer.getItems()) {
            inventoryRepository.findByProductIdAndWarehouseId(
                    item.getProductId(), transfer.getFromWarehouseId())
                    .ifPresent(srcInv -> {
                        int before = srcInv.getQuantity() != null ? srcInv.getQuantity() : 0;
                        srcInv.reverseDispatch(item.getQuantity());
                        inventoryRepository.save(srcInv);
                        inventoryService.recordTransaction(srcInv, transfer.getId(), "TRANSFER_REVERSE",
                                item.getQuantity(), before, srcInv.getQuantity(), rejectedBy.toString(),
                                "Hoàn hàng — kho nhập từ chối: " + reason);
                    });
        }

        transfer.setStatus(InternalTransfer.TransferStatus.REJECTED_BY_RECEIVER);
        transfer.setReceivedByUserId(rejectedBy);
        transfer.setReceivedAt(Instant.now());
        transfer.setCancelReason(reason);
        InternalTransfer savedTransfer = transferRepository.save(transfer);

        notificationService.notifyTransferRejectedByReceiver(savedTransfer);
        log.info("Transfer {} rejected by receiver: reason={}", savedTransfer.getCode(), reason);

        // Nếu là phiếu auto (gom hàng đơn online), kiểm tra toàn bộ phiếu của đơn
        // để tránh đơn bị kẹt mãi ở WAITING_FOR_CONSOLIDATION
        if (savedTransfer.getReferenceOrderId() != null) {
            UUID orderId = savedTransfer.getReferenceOrderId();
            transferRepository.flush();
            List<InternalTransfer> allTransfers = transferRepository.findByReferenceOrderId(orderId);

            boolean allTerminal = allTransfers.stream()
                    .allMatch(t -> t.getStatus() == InternalTransfer.TransferStatus.RECEIVED
                               || t.getStatus() == InternalTransfer.TransferStatus.RECEIVED_PARTIAL
                               || t.getStatus() == InternalTransfer.TransferStatus.REJECTED_BY_RECEIVER
                               || t.getStatus() == InternalTransfer.TransferStatus.CANCELLED);

            if (allTerminal) {
                orderRepository.findById(orderId).ifPresent(order -> {
                    if (order.getStatus() == Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
                        order.transitionTo(Order.OrderStatus.PENDING,
                                "Hệ thống: Phiếu chuyển kho bị từ chối — cần xử lý thủ công", "SYSTEM");
                        orderRepository.save(order);
                        log.warn("Order {} moved to PENDING after transfer rejected by receiver (manual review needed)",
                                order.getCode());
                    }
                });
            }
        }

        return savedTransfer;
    }

    // ─── HỦY — bắt buộc có lý do ─────────────────────────────────────────────
    @Transactional
    public InternalTransfer cancelTransfer(UUID transferId, UUID cancelledBy, String reason) {
        InternalTransfer transfer = transferRepository.findByIdWithItems(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", transferId));

        InternalTransfer.TransferStatus s = transfer.getStatus();
        if (s != InternalTransfer.TransferStatus.DRAFT
                && s != InternalTransfer.TransferStatus.PENDING_APPROVAL
                && s != InternalTransfer.TransferStatus.REJECTED) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể hủy phiếu ở trạng thái DRAFT, PENDING_APPROVAL hoặc REJECTED.");
        }
        if (transfer.getReferenceOrderId() != null) {
            throw new BusinessException("AUTO_TRANSFER_LOCKED",
                    "Không thể hủy thủ công phiếu chuyển kho gom hàng do hệ thống tự tạo.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do hủy.");
        }

        transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
        transfer.setCancelReason(reason);

        log.info("Transfer cancelled: {} by user: {}", transfer.getCode(), cancelledBy);
        return transferRepository.save(transfer);
    }

    @Transactional(readOnly = true)
    public Page<InternalTransfer> searchTransfers(UUID warehouseId, String statusStr,
            String keyword, Pageable pageable) {
        InternalTransfer.TransferStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try { status = InternalTransfer.TransferStatus.valueOf(statusStr.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        String kw = (keyword == null) ? "" : keyword.trim();

        if (warehouseId == null) {
            if (status == null) return transferRepository.searchAllTransfers(kw, pageable);
            else return transferRepository.searchAllTransfersWithStatus(status, kw, pageable);
        } else {
            if (status == null) return transferRepository.searchTransfersByWarehouse(warehouseId, kw, pageable);
            else return transferRepository.searchTransfersByWarehouseWithStatus(warehouseId, status, kw, pageable);
        }
    }

    @Transactional(readOnly = true)
    public InternalTransfer getById(UUID id) {
        return transferRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer", id));
    }

    @Transactional(readOnly = true)
    public List<InternalTransfer> getTransfersByOrderId(UUID orderId, UserPrincipal currentUser) {
        List<InternalTransfer> transfers = transferRepository.findByReferenceOrderId(orderId);
        if (currentUser.getRole() == User.UserRole.ROLE_ADMIN) return transfers;
        UUID userWarehouseId = currentUser.getWarehouseId();
        return transfers.stream()
                .filter(t -> (t.getFromWarehouseId() != null && t.getFromWarehouseId().equals(userWarehouseId))
                          || (t.getToWarehouseId() != null && t.getToWarehouseId().equals(userWarehouseId)))
                .toList();
    }

    public record TransferItemRequest(UUID productId, int quantity) {}
    public record ReceiveItemRequest(UUID productId, int receivedQty, String discrepancyReason) {}
}
