package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import sme.backend.entity.*;
import sme.backend.repository.NotificationRepository;
import sme.backend.repository.ProductRepository;
import sme.backend.repository.WarehouseRepository;
import sme.backend.repository.UserRepository;
import sme.backend.repository.InventoryRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationRepository notificationRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final InventoryRepository inventoryRepository;

    @jakarta.annotation.PostConstruct
    @org.springframework.transaction.annotation.Transactional
    public void cleanupExistingDuplicatesOnStartup() {
        try {
            log.info("🚀 [STARTUP] Đang tự động dọn dẹp các thông báo tồn kho trùng lặp trong cơ sở dữ liệu...");
            int deletedCount = notificationRepository.deleteDuplicateStockNotifications();
            log.info("✨ [STARTUP] Dọn dẹp hoàn tất! Đã xóa thành công {} thông báo trùng lặp cũ.", deletedCount);
        } catch (Exception e) {
            log.warn(
                    "⚠️ [STARTUP] Không thể chạy tự động dọn dẹp (có thể do DB không có dữ liệu hoặc không phải Postgres): {}",
                    e.getMessage());
        }
    }

    private void saveNotificationForRecipients(
            String type,
            String title,
            String message,
            Map<String, Object> payload,
            UUID warehouseId) {

        List<User> recipients = new java.util.ArrayList<>();

        if (warehouseId != null) {
            // Thêm tất cả các quản lý đang hoạt động của chi nhánh/kho đó
            recipients.addAll(userRepository.findActiveManagersByWarehouse(warehouseId));
        }

        // Thêm tất cả các Admin hệ thống
        recipients.addAll(userRepository.findByRoleAndIsActiveTrue(User.UserRole.ROLE_ADMIN));

        // Loại bỏ người nhận trùng lặp
        Map<UUID, User> uniqueRecipients = new java.util.HashMap<>();
        for (User u : recipients) {
            if (u.getId() != null) {
                uniqueRecipients.put(u.getId(), u);
            }
        }

        if (uniqueRecipients.isEmpty()) {
            Notification notification = Notification.builder()
                    .type(type)
                    .title(title)
                    .message(message)
                    .payload(payload)
                    .isRead(false)
                    .userId(null)
                    .build();
            notificationRepository.save(notification);
        } else {
            for (User recipient : uniqueRecipients.values()) {
                Notification notification = Notification.builder()
                        .type(type)
                        .title(title)
                        .message(message)
                        .payload(payload)
                        .isRead(false)
                        .userId(recipient.getId())
                        .build();
                notificationRepository.save(notification);
            }
        }
    }

    private UUID getWarehouseManagerOrAdmin(UUID warehouseId) {
        return warehouseRepository.findById(warehouseId).map(Warehouse::getManagerId)
                .orElseGet(() -> userRepository.findActiveManagersByWarehouse(warehouseId)
                        .stream().findFirst().map(User::getId)
                        .orElseGet(() -> userRepository.findByRoleAndIsActiveTrue(User.UserRole.ROLE_ADMIN)
                                .stream().findFirst().map(User::getId).orElse(null)));
    }

    @Async
    public void notifyLowStock(Inventory inventory) {
        if (inventory == null || inventory.getWarehouseId() == null)
            return; // An toàn trên hết

        int minQty = inventory.getMinQuantity() != null ? inventory.getMinQuantity() : 0;
        int currentQty = inventory.getQuantity() != null ? inventory.getQuantity() : 0;

        // Tránh lưu thông báo trùng số lượng gần nhất
        try {
            List<Notification> recentAlerts = notificationRepository.findRecentStockNotifications(
                    org.springframework.data.domain.PageRequest.of(0, 50));
            for (Notification n : recentAlerts) {
                if (n.getPayload() != null) {
                    Object wIdObj = n.getPayload().get("warehouseId");
                    Object pIdObj = n.getPayload().get("productId");
                    Object qtyObj = n.getPayload().get("quantity");
                    if (wIdObj != null && wIdObj.toString().equals(inventory.getWarehouseId().toString()) &&
                            pIdObj != null && pIdObj.toString().equals(inventory.getProductId().toString()) &&
                            qtyObj != null) {
                        try {
                            int qty = Double.valueOf(qtyObj.toString()).intValue();
                            if (qty == currentQty) {
                                log.info(
                                        "Cảnh báo tồn kho thấp cho sản phẩm {} tại kho {} với số lượng {} đã tồn tại, bỏ qua lưu trùng.",
                                        inventory.getProductId(), inventory.getWarehouseId(), currentQty);
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi kiểm tra trùng thông báo tồn kho: {}", e.getMessage());
        }

        String topic = "/topic/warehouse/" + inventory.getWarehouseId() + "/low-stock";

        // Truy vấn tên sản phẩm từ DB
        String productName = productRepository.findById(inventory.getProductId())
                .map(Product::getName)
                .orElse("Sản phẩm không xác định");

        // Truy vấn tên kho từ DB
        String warehouseName = warehouseRepository.findById(inventory.getWarehouseId())
                .map(Warehouse::getName)
                .orElse("Kho không xác định");

        // Payload đầy đủ gồm cả tên sản phẩm và tên kho
        Map<String, Object> payload = Map.of(
                "type", "LOW_STOCK",
                "productId", inventory.getProductId(),
                "warehouseId", inventory.getWarehouseId(),
                "quantity", currentQty,
                "minQuantity", minQty,
                "productName", productName,
                "warehouseName", warehouseName);
        // Tạo thông báo lưu vào DB với tên đầy đủ cho tất cả Quản lý kho & Admin
        saveNotificationForRecipients(
                "LOW_STOCK",
                "⚠️ Cảnh báo tồn kho thấp",
                String.format(
                        "⚠️ Cảnh báo: Sản phẩm '%s' tại kho '%s' sắp hết hàng. Hiện còn %d sản phẩm (Ngưỡng tối thiểu: %d).",
                        productName, warehouseName, currentQty, minQty),
                payload,
                inventory.getWarehouseId());

        messagingTemplate.convertAndSend(topic, payload);
        messagingTemplate.convertAndSend("/topic/admin/low-stock", payload);
        log.info("Low stock notification saved and sent for product: {} at warehouse: {}", productName, warehouseName);
    }

    @Async
    public void notifyOutOfStock(Inventory inventory) {
        if (inventory == null || inventory.getWarehouseId() == null)
            return;

        // Tránh lưu thông báo trùng số lượng gần nhất
        try {
            List<Notification> recentAlerts = notificationRepository.findRecentStockNotifications(
                    org.springframework.data.domain.PageRequest.of(0, 50));
            for (Notification n : recentAlerts) {
                if (n.getPayload() != null) {
                    Object wIdObj = n.getPayload().get("warehouseId");
                    Object pIdObj = n.getPayload().get("productId");
                    Object qtyObj = n.getPayload().get("quantity");
                    if (wIdObj != null && wIdObj.toString().equals(inventory.getWarehouseId().toString()) &&
                            pIdObj != null && pIdObj.toString().equals(inventory.getProductId().toString()) &&
                            qtyObj != null) {
                        try {
                            int qty = Double.valueOf(qtyObj.toString()).intValue();
                            if (qty == 0) {
                                log.info("Cảnh báo hết hàng cho sản phẩm {} tại kho {} đã tồn tại, bỏ qua lưu trùng.",
                                        inventory.getProductId(), inventory.getWarehouseId());
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi kiểm tra trùng thông báo hết hàng: {}", e.getMessage());
        }

        String topic = "/topic/warehouse/" + inventory.getWarehouseId() + "/low-stock";
        String productName = productRepository.findById(inventory.getProductId()).map(Product::getName)
                .orElse("Sản phẩm không xác định");
        String warehouseName = warehouseRepository.findById(inventory.getWarehouseId()).map(Warehouse::getName)
                .orElse("Kho không xác định");

        Map<String, Object> payload = Map.of(
                "type", "OUT_OF_STOCK",
                "productId", inventory.getProductId(),
                "warehouseId", inventory.getWarehouseId(),
                "quantity", 0,
                "productName", productName,
                "warehouseName", warehouseName);

        saveNotificationForRecipients(
                "OUT_OF_STOCK",
                "🛑 Hết hàng",
                String.format("🛑 Sản phẩm '%s' tại kho '%s' đã hết hàng!", productName, warehouseName),
                payload,
                inventory.getWarehouseId());

        messagingTemplate.convertAndSend(topic, payload);
        messagingTemplate.convertAndSend("/topic/admin/low-stock", payload);
    }

    @Async
    public void notifyImportSuccess(PurchaseOrder order, UUID warehouseId) {
        String topic = "/topic/warehouse/" + warehouseId + "/inventory";
        String warehouseName = warehouseRepository.findById(warehouseId).map(Warehouse::getName)
                .orElse("Kho không xác định");

        Map<String, Object> payload = Map.of(
                "type", "IMPORT_SUCCESS",
                "orderId", order.getId(),
                "orderCode", order.getCode(),
                "warehouseName", warehouseName);

        saveNotificationForRecipients(
                "IMPORT_SUCCESS",
                "✅ Nhập kho thành công",
                String.format("✅ Phiếu nhập kho %s đã được nhập thành công vào kho %s.", order.getCode(),
                        warehouseName),
                payload,
                warehouseId);

        messagingTemplate.convertAndSend(topic, payload);
        messagingTemplate.convertAndSend("/topic/admin/inventory", payload);
    }

    @Async
    public void notifyNewOrder(Order order, UUID warehouseId) {
        String topic = "/topic/warehouse/" + warehouseId + "/new-order";

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "NEW_ORDER");
        payload.put("orderId", order.getId());
        payload.put("orderCode", order.getCode());
        payload.put("amount", order.getFinalAmount() != null ? order.getFinalAmount() : 0);
        payload.put("type_order", order.getType() != null ? order.getType().name() : "DELIVERY");

        saveNotificationForRecipients(
                "NEW_ORDER",
                "🛒 Đơn hàng mới",
                String.format("🛒 Khách hàng vừa đặt đơn hàng online mới: %s. Trị giá: %,.0f VNĐ.",
                        order.getCode(), order.getFinalAmount() != null ? order.getFinalAmount() : 0),
                payload,
                warehouseId);

        messagingTemplate.convertAndSend(topic, payload);
        messagingTemplate.convertAndSend("/topic/admin/new-order", payload);

        log.debug("New order notification saved and sent: order={}", order.getCode());
    }

    @Async
    public void notifyShiftClosed(Shift shift) {
        String topic = "/topic/warehouse/" + shift.getWarehouseId() + "/shift-alert";

        Map<String, Object> payload = Map.of(
                "type", "SHIFT_PENDING_APPROVAL",
                "shiftId", shift.getId(),
                "cashierId", shift.getCashierId(),
                "discrepancyAmount", shift.getDiscrepancyAmount() != null ? shift.getDiscrepancyAmount() : 0);

        saveNotificationForRecipients(
                "SHIFT_PENDING_APPROVAL",
                "🔒 Đóng ca cần duyệt",
                String.format("🔒 Ca làm việc %s có sự chênh lệch tiền mặt. Vui lòng kiểm tra và duyệt.",
                        shift.getId()),
                payload,
                shift.getWarehouseId());

        messagingTemplate.convertAndSend(topic, payload);
        messagingTemplate.convertAndSend("/topic/admin/shift-alert", payload);
        log.debug("Shift closed notification saved and sent: shift={}", shift.getId());
    }

    @Async
    public void notifyTransferArrived(UUID transferId, UUID toWarehouseId) {
        String topic = "/topic/warehouse/" + toWarehouseId + "/transfer";

        Map<String, Object> payload = Map.of(
                "type", "TRANSFER_ARRIVED",
                "transferId", transferId);

        saveNotificationForRecipients(
                "TRANSFER_ARRIVED",
                "📦 Cập nhật trạng thái chuyển kho",
                "Một phiếu chuyển kho liên quan đến chi nhánh của bạn vừa được cập nhật.",
                payload,
                toWarehouseId);

        messagingTemplate.convertAndSend(topic, payload);
        messagingTemplate.convertAndSend("/topic/admin/transfer", payload);
    }

    // ─── APPROVAL WORKFLOW NOTIFICATIONS ─────────────────────────────────────

    private void notifyAdminsOnly(String type, String title, String message, Map<String, Object> payload) {
        List<User> admins = userRepository.findByRoleAndIsActiveTrue(User.UserRole.ROLE_ADMIN);
        for (User admin : admins) {
            Notification n = Notification.builder()
                    .type(type).title(title).message(message).payload(payload)
                    .isRead(false).userId(admin.getId()).build();
            notificationRepository.save(n);
        }
        messagingTemplate.convertAndSend("/topic/admin/approval", payload);
    }

    private void notifyManagersOfWarehouse(UUID warehouseId, String type, String title,
                                           String message, Map<String, Object> payload) {
        List<User> managers = userRepository.findActiveManagersByWarehouse(warehouseId);
        for (User mgr : managers) {
            Notification n = Notification.builder()
                    .type(type).title(title).message(message).payload(payload)
                    .isRead(false).userId(mgr.getId()).build();
            notificationRepository.save(n);
            messagingTemplate.convertAndSend("/topic/user/" + mgr.getId() + "/approval", payload);
        }
    }

    private void notifySpecificUser(UUID userId, String type, String title, String message, Map<String, Object> payload) {
        if (userId == null) return;
        Notification n = Notification.builder()
                .type(type).title(title).message(message).payload(payload)
                .isRead(false).userId(userId).build();
        notificationRepository.save(n);
        messagingTemplate.convertAndSend("/topic/user/" + userId + "/approval", payload);
    }

    @Async
    public void notifyPurchasePendingApproval(sme.backend.entity.PurchaseOrder po) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "PURCHASE_PENDING_APPROVAL");
        payload.put("orderId", po.getId());
        payload.put("orderCode", po.getCode());
        if (po.getWarehouseId() != null) payload.put("warehouseId", po.getWarehouseId());

        String title = "📋 Phiếu nhập chờ duyệt";
        String msg = String.format("Phiếu nhập kho %s đang chờ duyệt.", po.getCode());

        // Duyệt chéo: Manager tạo → Admin duyệt; Admin tạo → Manager kho liên quan duyệt
        if ("ROLE_ADMIN".equals(po.getCreatorRole()) && po.getWarehouseId() != null) {
            notifyManagersOfWarehouse(po.getWarehouseId(),
                    "PURCHASE_PENDING_APPROVAL", title, msg, payload);
        } else {
            notifyAdminsOnly("PURCHASE_PENDING_APPROVAL", title, msg, payload);
        }
    }

    @Async
    public void notifyPurchaseApproved(sme.backend.entity.PurchaseOrder po) {
        Map<String, Object> payload = Map.of(
                "type", "PURCHASE_APPROVED",
                "orderId", po.getId(),
                "orderCode", po.getCode());
        notifySpecificUser(po.getCreatedByUserId(), "PURCHASE_APPROVED", "✅ Phiếu nhập đã duyệt",
                String.format("Phiếu nhập %s đã được duyệt. Vui lòng tiến hành nhận hàng.", po.getCode()), payload);
    }

    @Async
    public void notifyPurchaseRejected(sme.backend.entity.PurchaseOrder po) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "PURCHASE_REJECTED");
        payload.put("orderId", po.getId());
        payload.put("orderCode", po.getCode());
        payload.put("reason", po.getRejectionReason());
        notifySpecificUser(po.getCreatedByUserId(), "PURCHASE_REJECTED", "❌ Phiếu nhập bị từ chối",
                String.format("Phiếu nhập %s bị từ chối. Lý do: %s", po.getCode(), po.getRejectionReason()), payload);
    }

    @Async
    public void notifyTransferPendingApproval(sme.backend.entity.InternalTransfer transfer) {
        Map<String, Object> payload = Map.of(
                "type", "TRANSFER_PENDING_APPROVAL",
                "transferId", transfer.getId(),
                "transferCode", transfer.getCode());
        String title = "📦 Phiếu chuyển kho chờ duyệt";
        boolean isAutoTransfer = transfer.getReferenceOrderId() != null;
        // Phiếu tự động: kho xuất (fromWarehouse) duyệt. Phiếu thủ công: người tạo luôn ở kho xuất,
        // nên kho nhận hàng (toWarehouse) mới là người cần xác nhận đồng ý nhận.
        java.util.UUID approverWarehouseId = isAutoTransfer ? transfer.getFromWarehouseId() : transfer.getToWarehouseId();
        String message = isAutoTransfer
                ? String.format("Phiếu chuyển kho %s đang chờ kho xuất hàng duyệt.", transfer.getCode())
                : String.format("Phiếu chuyển kho %s đang chờ kho nhận hàng xác nhận.", transfer.getCode());
        notifyAdminsOnly("TRANSFER_PENDING_APPROVAL", title, message, payload);
        if (approverWarehouseId != null) {
            notifyManagersOfWarehouse(approverWarehouseId, "TRANSFER_PENDING_APPROVAL", title, message, payload);
        }
    }

    @Async
    public void notifyTransferApproved(sme.backend.entity.InternalTransfer transfer) {
        Map<String, Object> payload = Map.of(
                "type", "TRANSFER_APPROVED",
                "transferId", transfer.getId(),
                "transferCode", transfer.getCode());
        notifySpecificUser(transfer.getCreatedByUserId(), "TRANSFER_APPROVED", "✅ Phiếu chuyển kho đã duyệt",
                String.format("Phiếu chuyển %s đã duyệt. Có thể xuất kho.", transfer.getCode()), payload);
    }

    @Async
    public void notifyTransferRejected(sme.backend.entity.InternalTransfer transfer) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "TRANSFER_REJECTED");
        payload.put("transferId", transfer.getId());
        payload.put("transferCode", transfer.getCode());
        payload.put("reason", transfer.getRejectionReason());
        notifySpecificUser(transfer.getCreatedByUserId(), "TRANSFER_REJECTED", "❌ Phiếu chuyển kho bị từ chối",
                String.format("Phiếu chuyển %s bị từ chối. Lý do: %s",
                        transfer.getCode(), transfer.getRejectionReason()), payload);
    }

    @Async
    public void notifyTransferRejectedByReceiver(sme.backend.entity.InternalTransfer transfer) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "TRANSFER_REJECTED_BY_RECEIVER");
        payload.put("transferId", transfer.getId());
        payload.put("transferCode", transfer.getCode());
        payload.put("reason", transfer.getCancelReason());
        // Thông báo cho người tạo phiếu (kho nguồn) biết hàng bị từ chối
        notifySpecificUser(transfer.getCreatedByUserId(), "TRANSFER_REJECTED_BY_RECEIVER",
                "📦 Kho nhập từ chối nhận hàng",
                String.format("Kho nhập đã từ chối nhận hàng phiếu %s. Hàng đã được hoàn về kho xuất. Lý do: %s",
                        transfer.getCode(), transfer.getCancelReason()), payload);
        // Cũng thông báo cho Admin
        notifyAdminsOnly("TRANSFER_REJECTED_BY_RECEIVER",
                "📦 Kho nhập từ chối nhận hàng",
                String.format("Kho nhập đã từ chối nhận hàng phiếu %s. Lý do: %s",
                        transfer.getCode(), transfer.getCancelReason()), payload);
    }

    @Async
    public void notifyTransferReceivedPartial(sme.backend.entity.InternalTransfer transfer) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "TRANSFER_RECEIVED_PARTIAL");
        payload.put("transferId", transfer.getId().toString());
        payload.put("transferCode", transfer.getCode());

        // Tổng hợp chi tiết chênh lệch
        int totalShortItems = 0;
        int totalShortQty = 0;
        StringBuilder detail = new StringBuilder();
        for (sme.backend.entity.TransferItem item : transfer.getItems()) {
            if (item.getDiscrepancyQty() != null && item.getDiscrepancyQty() > 0) {
                totalShortItems++;
                totalShortQty += item.getDiscrepancyQty();
                detail.append(String.format("• Sản phẩm (%s): thiếu %d — %s. ",
                        item.getProductId().toString().substring(0, 8),
                        item.getDiscrepancyQty(),
                        item.getDiscrepancyReason() != null ? item.getDiscrepancyReason() : "không rõ lý do"));
            }
        }

        payload.put("totalShortItems", totalShortItems);
        payload.put("totalShortQty", totalShortQty);

        String title = "⚠️ Phiếu chuyển kho nhận thiếu hàng";
        String message = String.format(
                "Kho nhập đã nhận phiếu %s nhưng thiếu %d sản phẩm (tổng %d đơn vị). %s" +
                "Vui lòng kiểm tra và tạo phiếu bổ sung nếu cần.",
                transfer.getCode(), totalShortItems, totalShortQty, detail.toString());

        // Thông báo cho managers kho xuất (cần nhận thông tin thiếu hàng)
        notifyManagersOfWarehouse(transfer.getFromWarehouseId(),
                "TRANSFER_RECEIVED_PARTIAL", title, message, payload);

        // Thông báo cho Admin
        notifyAdminsOnly("TRANSFER_RECEIVED_PARTIAL", title, message, payload);

        // WebSocket real-time
        messagingTemplate.convertAndSend(
                "/topic/warehouse/" + transfer.getFromWarehouseId() + "/transfer", payload);
        messagingTemplate.convertAndSend("/topic/admin/transfer", payload);

        log.debug("Transfer partial receipt notification sent: transfer={}, shortItems={}, shortQty={}",
                transfer.getCode(), totalShortItems, totalShortQty);
    }

    @Async
    public void notifyTransferReceivedFull(sme.backend.entity.InternalTransfer transfer) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "TRANSFER_RECEIVED_FULL");
        payload.put("transferId", transfer.getId().toString());
        payload.put("transferCode", transfer.getCode());

        String title = "✅ Phiếu chuyển kho đã nhận đủ";
        String message = String.format("Kho nhập đã xác nhận nhận đủ hàng phiếu %s.", transfer.getCode());

        notifyManagersOfWarehouse(transfer.getFromWarehouseId(),
                "TRANSFER_RECEIVED_FULL", title, message, payload);
        notifyAdminsOnly("TRANSFER_RECEIVED_FULL", title, message, payload);

        messagingTemplate.convertAndSend(
                "/topic/warehouse/" + transfer.getFromWarehouseId() + "/transfer", payload);
        messagingTemplate.convertAndSend("/topic/admin/transfer", payload);
    }

    @Async
    public void notifyAdjustmentPendingApproval(sme.backend.entity.StockAdjustment adj) {
        Map<String, Object> payload = Map.of(
                "type", "ADJUSTMENT_PENDING_APPROVAL",
                "adjustmentId", adj.getId(),
                "adjustmentCode", adj.getCode(),
                "warehouseId", adj.getWarehouseId());
        notifyAdminsOnly("ADJUSTMENT_PENDING_APPROVAL", "📊 Phiếu kiểm kê chờ duyệt",
                String.format("Phiếu kiểm kê %s đang chờ duyệt.", adj.getCode()), payload);
    }

    // ── Supplier Return Notifications ────────────────────────────────────────

    @Async
    public void notifySupplierReturnPendingApproval(sme.backend.entity.SupplierReturn sr) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "SUPPLIER_RETURN_PENDING_APPROVAL");
        payload.put("returnId", sr.getId());
        payload.put("returnCode", sr.getCode());
        payload.put("warehouseId", sr.getWarehouseId());
        saveNotificationForRecipients("SUPPLIER_RETURN_PENDING_APPROVAL",
                "📦 Phiếu hoàn trả NCC chờ duyệt",
                String.format("Phiếu hoàn trả NCC %s đang chờ phê duyệt.", sr.getCode()),
                payload, sr.getWarehouseId());
    }

    @Async
    public void notifySupplierReturnApproved(sme.backend.entity.SupplierReturn sr) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "SUPPLIER_RETURN_APPROVED");
        payload.put("returnId", sr.getId());
        payload.put("returnCode", sr.getCode());
        if (sr.getSubmittedBy() != null) {
            notifySpecificUser(sr.getSubmittedBy(), "SUPPLIER_RETURN_APPROVED",
                    "✅ Phiếu hoàn trả NCC đã được duyệt",
                    String.format("Phiếu hoàn trả NCC %s đã được phê duyệt. Có thể tiến hành xuất kho.", sr.getCode()),
                    payload);
        }
    }

    @Async
    public void notifySupplierReturnRejected(sme.backend.entity.SupplierReturn sr) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "SUPPLIER_RETURN_REJECTED");
        payload.put("returnId", sr.getId());
        payload.put("returnCode", sr.getCode());
        payload.put("reason", sr.getRejectionReason());
        if (sr.getSubmittedBy() != null) {
            notifySpecificUser(sr.getSubmittedBy(), "SUPPLIER_RETURN_REJECTED",
                    "❌ Phiếu hoàn trả NCC bị từ chối",
                    String.format("Phiếu hoàn trả NCC %s bị từ chối. Lý do: %s", sr.getCode(), sr.getRejectionReason()),
                    payload);
        }
    }

    public void saveNotification(sme.backend.entity.Notification notif) {
        notificationRepository.save(notif);
    }

    public void markAsRead(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.setIsRead(true);
            notificationRepository.save(n);
        });
    }

    @org.springframework.transaction.annotation.Transactional
    public void markAllAsRead(UUID userId) {
        if (userId == null) {
            notificationRepository.markAllUnreadAsReadForAdmin();
        } else {
            notificationRepository.markAllUnreadAsReadForUser(userId);
        }
    }

    private List<Notification> filterNotificationsByWarehouse(List<Notification> list, UUID userWarehouseId) {
        if (list == null)
            return List.of();
        if (userWarehouseId == null)
            return list; // Admin sees everything!
        return list.stream()
                .filter(n -> {
                    if (n.getPayload() != null && n.getPayload().containsKey("warehouseId")) {
                        Object wIdObj = n.getPayload().get("warehouseId");
                        if (wIdObj != null) {
                            try {
                                UUID wId = UUID.fromString(wIdObj.toString());
                                return wId.equals(userWarehouseId);
                            } catch (Exception e) {
                                return false;
                            }
                        }
                    }
                    return true;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    private void generateMissingLowStockNotifications(UUID userId, UUID warehouseId) {
        try {
            List<Notification> recentAlerts = notificationRepository.findRecentStockNotifications(
                    org.springframework.data.domain.PageRequest.of(0, 100));

            Map<UUID, Integer> notifiedProductQuantities = new java.util.HashMap<>();
            for (Notification n : recentAlerts) {
                if (n.getPayload() != null) {
                    Object wIdObj = n.getPayload().get("warehouseId");
                    if (wIdObj != null && wIdObj.toString().equals(warehouseId.toString())) {
                        Object pIdObj = n.getPayload().get("productId");
                        Object qtyObj = n.getPayload().get("quantity");
                        if (pIdObj != null && qtyObj != null) {
                            try {
                                UUID pId = UUID.fromString(pIdObj.toString());
                                int qty = Double.valueOf(qtyObj.toString()).intValue();
                                if (!notifiedProductQuantities.containsKey(pId)) {
                                    notifiedProductQuantities.put(pId, qty);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }

            List<Inventory> inventories = inventoryRepository.findByWarehouseId(warehouseId);
            for (Inventory inv : inventories) {
                int minQty = inv.getMinQuantity() != null ? inv.getMinQuantity() : 0;
                int currentQty = inv.getQuantity() != null ? inv.getQuantity() : 0;

                if (minQty > 0 && currentQty < minQty) {
                    Integer lastNotifiedQty = notifiedProductQuantities.get(inv.getProductId());
                    if (lastNotifiedQty == null || lastNotifiedQty != currentQty) {
                        if (currentQty <= 0) {
                            this.notifyOutOfStock(inv);
                        } else {
                            this.notifyLowStock(inv);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Lỗi tự động sinh thông báo tồn kho thấp: {}", e.getMessage(), e);
        }
    }

    public List<Notification> getUnread(UUID userId) {
        if (userId == null) {
            return notificationRepository.findAllUnread();
        }

        UUID userWarehouseId = userRepository.findById(userId).map(User::getWarehouseId).orElse(null);
        if (userWarehouseId != null) {
            generateMissingLowStockNotifications(userId, userWarehouseId);
        }

        List<Notification> list = notificationRepository.findForUserAndGlobalUnread(userId);
        return filterNotificationsByWarehouse(list, userWarehouseId);
    }

    public long countUnread(UUID userId) {
        if (userId == null) {
            return notificationRepository.countAllUnread();
        }
        return getUnread(userId).size();
    }

    public Page<Notification> getAll(UUID userId, Pageable pageable) {
        if (userId == null) {
            return notificationRepository.findAllNotifications(pageable);
        }

        UUID userWarehouseId = userRepository.findById(userId).map(User::getWarehouseId).orElse(null);
        List<Notification> allNotifications = notificationRepository.findForUserAndGlobalList(userId);

        List<Notification> filteredList = filterNotificationsByWarehouse(allNotifications, userWarehouseId);

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filteredList.size());
        List<Notification> pageContent = new java.util.ArrayList<>();
        if (start < filteredList.size()) {
            pageContent = filteredList.subList(start, end);
        }
        return new org.springframework.data.domain.PageImpl<>(pageContent, pageable, filteredList.size());
    }
}