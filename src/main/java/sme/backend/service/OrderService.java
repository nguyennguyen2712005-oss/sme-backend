package sme.backend.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreateOrderRequest;
import sme.backend.dto.response.OrderResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final InventoryService inventoryService;
    private final NotificationService notificationService;
    private final CashbookTransactionRepository cashbookRepository;
    private final InternalTransferRepository transferRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final InvoiceRepository invoiceRepository;
    private final ProductReviewRepository productReviewRepository; // <-- ĐÃ THÊM
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    private final EntityManager entityManager;



    private UUID getCurrentUserIdSafe() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null; // Trả về null nếu guest
        }
        try {
            Object principal = auth.getPrincipal();
            java.lang.reflect.Method method = principal.getClass().getMethod("getId");
            Object id = method.invoke(principal);
            if (id instanceof UUID)
                return (UUID) id;
            if (id != null)
                return UUID.fromString(id.toString());
        } catch (Exception e) {
            log.warn("Không lấy được ID qua Token, chuyển sang quét DB bằng username...");
        }
        try {
            String username = auth.getName();
            if (username != null && !username.isEmpty()) {
                List<?> results = entityManager
                        .createNativeQuery("SELECT id FROM users WHERE username = :username LIMIT 1")
                        .setParameter("username", username)
                        .getResultList();
                if (!results.isEmpty() && results.get(0) != null) {
                    return UUID.fromString(results.get(0).toString());
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi khi query bảng users: {}", e.getMessage());
        }
        return null;
    }

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest req) {
        Customer customer = customerRepository.findById(req.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.getCustomerId()));

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        UUID currentUserId = getCurrentUserIdSafe();

        for (CreateOrderRequest.OrderItemRequest itemReq : req.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId()).orElseThrow();

            Integer totalAvailObj = inventoryRepository.getTotalAvailableQuantity(product.getId());
            int available = totalAvailObj != null ? totalAvailObj : 0;

            if (available < itemReq.getQuantity()) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Sản phẩm '" + product.getName() + "' không đủ tồn kho trên toàn hệ thống.");
            }
            BigDecimal subtotal = product.getRetailPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            orderItems.add(OrderItem.builder()
                    .productId(product.getId())
                    .quantity(itemReq.getQuantity())
                    .unitPrice(product.getRetailPrice())
                    .macPrice(product.getMacPrice() != null ? product.getMacPrice() : BigDecimal.ZERO)
                    .subtotal(subtotal)
                    .build());
            totalAmount = totalAmount.add(subtotal);
        }

        BigDecimal shippingFee = req.getShippingFee() != null ? req.getShippingFee() : BigDecimal.ZERO;
        BigDecimal discountAmount = req.getDiscountAmount() != null ? req.getDiscountAmount() : BigDecimal.ZERO;
        
        // --- Xử lý Loyalty Points ---
        Integer pointsToUse = req.getPointsToUse() != null ? req.getPointsToUse() : 0;
        BigDecimal pointsDiscount = BigDecimal.ZERO;
        if (pointsToUse > 0) {
            if (customer.getLoyaltyPoints() == null || customer.getLoyaltyPoints() < pointsToUse) {
                throw new BusinessException("INSUFFICIENT_POINTS", "Điểm tích lũy không đủ.");
            }
            if (pointsToUse % 500 != 0) {
                throw new BusinessException("INVALID_POINTS", "Số điểm quy đổi phải là bội số của 500.");
            }
            // 1 điểm = 100 VND (đồng bộ với POSService và Config)
            pointsDiscount = BigDecimal.valueOf(pointsToUse).multiply(BigDecimal.valueOf(100));
            customer.deductPoints(pointsToUse);
            customerRepository.save(customer);
        }

        BigDecimal finalAmount = totalAmount.add(shippingFee).subtract(discountAmount).subtract(pointsDiscount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            finalAmount = BigDecimal.ZERO;
        }

        UUID assignedWarehouseId = null;
        Map<String, Object> chosenPlan = null;

        List<Map<String, Object>> plans = suggestBranchesForOrder(req.getProvinceCode(), req.getShippingLatitude(), req.getShippingLongitude(), req.getItems());
        if (plans.isEmpty())
            throw new BusinessException("NO_WAREHOUSE", "Không đủ hàng để gom chung 1 kiện trên toàn hệ thống.");

        // Chọn kho tối ưu nhất (ở index 0 vì list đã được sort)
        chosenPlan = plans.get(0);
        assignedWarehouseId = (UUID) chosenPlan.get("warehouseId");

        if (chosenPlan == null)
            throw new BusinessException("INVALID_WAREHOUSE", "Kho được chọn không hợp lệ.");

        Order.OrderType orderType = "BOPIS".equalsIgnoreCase(req.getType()) ? Order.OrderType.BOPIS
                : Order.OrderType.DELIVERY;
        boolean isReadyToShip = (Boolean) chosenPlan.get("isReadyToShip");

        // Logic trạng thái theo paymentMethod
        Order.OrderStatus initialStatus;
        if ("COD".equalsIgnoreCase(req.getPaymentMethod())) {
            initialStatus = isReadyToShip ? Order.OrderStatus.PENDING : Order.OrderStatus.WAITING_FOR_CONSOLIDATION;
        } else {
            initialStatus = Order.OrderStatus.PAYMENT_PENDING;
        }

        Order order = Order.builder()
                .code(generateOrderCode()).customerId(customer.getId()).assignedWarehouseId(assignedWarehouseId)
                .type(orderType).shippingName(req.getShippingName()).shippingPhone(req.getShippingPhone())
                .shippingAddress(req.getShippingAddress()).provinceCode(req.getProvinceCode())
                .shippingLatitude(req.getShippingLatitude())
                .shippingLongitude(req.getShippingLongitude())
                .totalAmount(totalAmount)
                .shippingFee(shippingFee).discountAmount(discountAmount).finalAmount(finalAmount).paymentMethod(req.getPaymentMethod())
                .paymentStatus(Order.PaymentStatus.UNPAID).note(req.getNote())
                .status(initialStatus)
                .build();
        orderItems.forEach(order::addItem);
        order = orderRepository.save(order);

        // Đặt trước hàng tồn kho tại kho được chỉ định
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> availableItems = (List<Map<String, Object>>) chosenPlan.get("availableItems");
        inventoryService.reserveForOnlineOrderBatch(availableItems, assignedWarehouseId, order.getId(), "SYSTEM");

        // Nếu cần tạo InternalTransfer để gom hàng
        if (!isReadyToShip) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transferReqs = (List<Map<String, Object>>) chosenPlan.get("transferRequirements");

            Map<UUID, List<Map<String, Object>>> transfersBySource = transferReqs.stream()
                    .collect(Collectors.groupingBy(reqMap -> (UUID) reqMap.get("fromWarehouseId")));

            for (Map.Entry<UUID, List<Map<String, Object>>> entry : transfersBySource.entrySet()) {
                UUID sourceWarehouseId = entry.getKey();

                UUID transferCreatorId = null;
                boolean isEmployee = false;
                if (currentUserId != null) {
                    isEmployee = userRepository.existsById(currentUserId);
                }
                
                if (isEmployee) {
                    transferCreatorId = currentUserId;
                } else {
                    transferCreatorId = userRepository.findByRoleAndIsActiveTrue(User.UserRole.ROLE_ADMIN)
                            .stream().findFirst().map(User::getId).orElse(null);
                    if (transferCreatorId == null) {
                        transferCreatorId = userRepository.findAllActive().stream().findFirst().map(User::getId).orElse(null);
                    }
                    if (transferCreatorId == null) {
                        throw new BusinessException("SYSTEM_ERROR", "Không thể tự động luân chuyển kho: Hệ thống không có nhân viên nào đang hoạt động.");
                    }
                }

                InternalTransfer transfer = InternalTransfer.builder()
                        .code("TRF-AUTO-" + System.currentTimeMillis() + "-"
                                + sourceWarehouseId.toString().substring(0, 4))
                        .fromWarehouseId(sourceWarehouseId).toWarehouseId(assignedWarehouseId)
                        .createdByUserId(transferCreatorId) // Gán ID Nhân viên hợp lệ
                        .status(InternalTransfer.TransferStatus.DRAFT)
                        .referenceOrderId(order.getId())
                        .note("Tự động tạo - Gom hàng cho Đơn #" + order.getCode())
                        .build();

                if (transfer.getItems() == null) {
                    transfer.setItems(new ArrayList<>());
                }

                for (Map<String, Object> reqItem : entry.getValue()) {
                    UUID pId = (UUID) reqItem.get("productId");
                    int qty = (Integer) reqItem.get("quantity");
                    transfer.addItem(TransferItem.builder().productId(pId).quantity(qty).build());
                }
                inventoryService.reserveForOnlineOrderBatch(entry.getValue(), sourceWarehouseId, order.getId(),
                        "SYSTEM_CONSOLIDATION");
                transferRepository.save(transfer);
                notificationService.notifyTransferArrived(transfer.getId(), sourceWarehouseId);
            }
        }

        notificationService.notifyNewOrder(order, assignedWarehouseId);
        
        // Cố gắng gửi email cho khách
        if (customer.getEmail() != null && !customer.getEmail().isBlank()) {
            try {
                emailService.sendOrderStatusEmail(
                    customer.getEmail(),
                    customer.getFullName() != null ? customer.getFullName() : order.getShippingName(),
                    order.getCode(),
                    order.getStatus().name(),
                    order.getFinalAmount().doubleValue()
                );
            } catch (Exception e) {
                log.warn("Không thể gửi email cho khách: {}", e.getMessage());
            }
        }

        return mapToResponse(order);
    }

    // ─────────────────────────────────────────────────────────
    // NV-1: Named Constants cho thuật toán Smart Routing
    // ─────────────────────────────────────────────────────────
    private static final int SCORE_READY_TO_SHIP       = 1000;
    private static final int SCORE_MAX_DISTANCE         = 500;
    private static final int DISTANCE_PENALTY_PER_KM    = 10;
    private static final double MAX_DISTANCE_KM         = 50.0;
    private static final int OVER_DISTANCE_PENALTY      = -300;
    private static final int TRANSFER_PENALTY_PER_ITEM  = 10;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> suggestBranchesForOrder(String provinceCode,
            Double shippingLat, Double shippingLng,
            List<CreateOrderRequest.OrderItemRequest> items) {
        if (items == null || items.isEmpty())
            return List.of();

        // NV-1: Kiểm tra xem có tọa độ giao hàng không, dùng cho tính khoảng cách
        boolean hasCoordinates = (shippingLat != null && shippingLng != null);

        List<Warehouse> activeWarehouses = warehouseRepository.findByIsActiveTrueOrderByName();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        List<UUID> productIds = items.stream().map(CreateOrderRequest.OrderItemRequest::getProductId).distinct().toList();

        // NV-5: Build Map for Product Names to avoid N+1 inside loop
        Map<UUID, String> productNameMap = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Product::getName));

        List<Inventory> allInventories = inventoryRepository.findByProductIdIn(productIds);
        Map<UUID, Map<UUID, Integer>> stockMatrix = new HashMap<>();
        for (Inventory inv : allInventories) {
            stockMatrix.computeIfAbsent(inv.getWarehouseId(), k -> new HashMap<>())
                    .put(inv.getProductId(), inv.getAvailableQuantity());
        }

        for (Warehouse targetWarehouse : activeWarehouses) {
            boolean isSameProvince = provinceCode != null && provinceCode.equals(targetWarehouse.getProvinceCode());
            List<Map<String, Object>> availableItems = new ArrayList<>();
            List<Map<String, Object>> transferRequirements = new ArrayList<>();
            boolean isReadyToShip = true;
            Map<UUID, Integer> targetStock = stockMatrix.getOrDefault(targetWarehouse.getId(), Collections.emptyMap());

            for (CreateOrderRequest.OrderItemRequest item : items) {
                int requiredQty = item.getQuantity();
                int currentStock = targetStock.getOrDefault(item.getProductId(), 0);

                if (currentStock >= requiredQty) {
                    availableItems.add(Map.of("productId", item.getProductId(), "quantity", requiredQty));
                } else {
                    isReadyToShip = false;
                    if (currentStock > 0) {
                        availableItems.add(Map.of("productId", item.getProductId(), "quantity", currentStock));
                    }
                    int missingQty = requiredQty - currentStock;
                    int remainingToFind = missingQty;
                    for (Warehouse sourceWarehouse : activeWarehouses) {
                        if (sourceWarehouse.getId().equals(targetWarehouse.getId()))
                            continue;
                        if (remainingToFind <= 0)
                            break;

                        Map<UUID, Integer> sourceStockMap = stockMatrix.getOrDefault(sourceWarehouse.getId(),
                                Collections.emptyMap());
                        int sourceStock = sourceStockMap.getOrDefault(item.getProductId(), 0);

                        if (sourceStock > 0) {
                            int takeQty = Math.min(sourceStock, remainingToFind);
                            remainingToFind -= takeQty;
                            String prodName = productNameMap.getOrDefault(item.getProductId(), "Unknown");
                            transferRequirements.add(Map.of(
                                    "fromWarehouseId", sourceWarehouse.getId(),
                                    "fromWarehouseName", sourceWarehouse.getName(),
                                    "productId", item.getProductId(),
                                    "productName", prodName,
                                    "quantity", takeQty));
                        }
                    }
                    if (remainingToFind > 0) {
                        isReadyToShip = false;
                        transferRequirements.clear();
                        break;
                    }
                }
            }
            if (!isReadyToShip && transferRequirements.isEmpty())
                continue;

            // ─────────────────────────────────────────────────────────
            // NV-1: Tính Score với Geographic Distance
            // ─────────────────────────────────────────────────────────
            int score = 0;

            // Yếu tố 1: Kho có sẵn đủ hàng
            if (isReadyToShip) {
                score += SCORE_READY_TO_SHIP;
            }

            // Yếu tố 2: Khoảng cách địa lý
            double distanceKm = Double.MAX_VALUE;
            int distanceScore = 0;

            if (hasCoordinates
                    && targetWarehouse.getLatitude() != null
                    && targetWarehouse.getLongitude() != null) {
                // Tính khoảng cách thực tế bằng Haversine
                distanceKm = sme.backend.util.HaversineUtils.calculateDistance(
                        shippingLat, shippingLng,
                        targetWarehouse.getLatitude(), targetWarehouse.getLongitude());

                if (distanceKm <= MAX_DISTANCE_KM) {
                    // Trong bán kính: tính điểm tuyến tính suy giảm theo khoảng cách
                    distanceScore = (int) Math.max(0,
                            SCORE_MAX_DISTANCE - (distanceKm * DISTANCE_PENALTY_PER_KM));
                } else {
                    // Ngoài bán kính: 0 điểm + penalty mạnh, nhưng vẫn giữ trong list
                    distanceScore = OVER_DISTANCE_PENALTY;
                }
            } else if (isSameProvince) {
                // FALLBACK: Không có tọa độ → lùi về logic isSameProvince cũ
                distanceScore = SCORE_MAX_DISTANCE;
            }
            // Nếu cả 2 đều null → distanceScore = 0 (kho này không có lợi thế địa lý)

            score += distanceScore;

            // Yếu tố 3: Penalty cho việc phải điều chuyển nội bộ
            score -= transferRequirements.size() * TRANSFER_PENALTY_PER_ITEM;

            Map<String, Object> plan = new HashMap<>();
            plan.put("warehouseId", targetWarehouse.getId());
            plan.put("warehouseName", targetWarehouse.getName());
            plan.put("isSameProvince", isSameProvince);
            plan.put("isReadyToShip", isReadyToShip);
            plan.put("distanceKm", distanceKm == Double.MAX_VALUE ? null : Math.round(distanceKm * 10.0) / 10.0);
            plan.put("availableItems", availableItems);
            plan.put("transferRequirements", transferRequirements);
            plan.put("sortScore", score);
            suggestions.add(plan);
        }
        suggestions.sort((a, b) -> Integer.compare((Integer) b.get("sortScore"), (Integer) a.get("sortScore")));
        return suggestions;
    }

    @Transactional
    public OrderResponse updateStatus(UUID orderId, String newStatus, String note, String trackingCode,
            String shippingProvider, String changedBy) {
        Order order = orderRepository.findByIdWithDetails(orderId).orElseThrow();
        Order.OrderStatus status = Order.OrderStatus.valueOf(newStatus.toUpperCase());
        order.transitionTo(status, note, changedBy);

        if (trackingCode != null)
            order.setTrackingCode(trackingCode);
        if (shippingProvider != null)
            order.setShippingProvider(shippingProvider);

        if (status == Order.OrderStatus.PACKING) {
            try {
                order.setPackedBy(UUID.fromString(changedBy));
            } catch (Exception ignored) {
            }
            order.setPackedAt(Instant.now());
        }

        if ((status == Order.OrderStatus.SHIPPING
                || (status == Order.OrderStatus.DELIVERED && order.getType() == Order.OrderType.BOPIS))
                && order.getAssignedWarehouseId() != null) {
            boolean alreadyShipped = order.getStatusHistory().stream()
                    .anyMatch(h -> "SHIPPING".equals(h.getNewStatus()));
            if (!alreadyShipped || status == Order.OrderStatus.SHIPPING) {
                order.getItems().forEach(item -> inventoryService.confirmOnlineShipment(item.getProductId(),
                        order.getAssignedWarehouseId(), item.getQuantity(), orderId, changedBy));
            }
        }

        if (status == Order.OrderStatus.CANCELLED) {
            doCancelOrderWithCleanup(order, note, changedBy);
            order.setCancelledReason(note);
        }

        if (status == Order.OrderStatus.RETURNED && order.getAssignedWarehouseId() != null) {
            order.getItems().forEach(item -> {
                inventoryService.returnToStock(
                    item.getProductId(), order.getAssignedWarehouseId(), item.getQuantity(), orderId, "RETURNED_ORDER",
                    changedBy);
                // NV-2: Dùng native query decrement an toàn
                productRepository.decrementSoldQuantity(item.getProductId(), item.getQuantity());
            });
        }

        if (status == Order.OrderStatus.DELIVERED) {
            order.getItems().forEach(item -> {
                // NV-2: Dùng native query increment an toàn
                productRepository.incrementSoldQuantity(item.getProductId(), item.getQuantity());
            });
            if ("COD".equals(order.getPaymentMethod())) {
                order.setPaymentStatus(Order.PaymentStatus.PAID);
                recordCODRevenue(order);
            } else if ("BANK_TRANSFER".equals(order.getPaymentMethod())
                    && order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
                order.setPaymentStatus(Order.PaymentStatus.PAID);
                recordBankTransferRevenue(order, changedBy);
            } else if ("CASH".equals(order.getPaymentMethod())
                    && order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
                order.setPaymentStatus(Order.PaymentStatus.PAID);
                recordCashRevenue(order, changedBy);
            }
            
            // Cập nhật tổng chi tiêu và điểm tích lũy cho khách hàng (tránh cộng dồn nếu đã DELIVERED trước đó)
            boolean alreadyDelivered = order.getStatusHistory().stream()
                    .anyMatch(h -> "DELIVERED".equals(h.getOldStatus()));
            if (!alreadyDelivered && order.getCustomerId() != null) {
                Customer customer = customerRepository.findById(order.getCustomerId()).orElse(null);
                if (customer != null) {
                    BigDecimal finalAmount = order.getFinalAmount();
                    int pointsEarned = finalAmount.divide(new BigDecimal("10000"), 0, java.math.RoundingMode.DOWN).intValue();
                    customer.addPoints(pointsEarned);
                    customer.setTotalSpent((customer.getTotalSpent() != null ? customer.getTotalSpent() : BigDecimal.ZERO).add(finalAmount));
                    customerRepository.save(customer);
                }
            }
        }

        if (status == Order.OrderStatus.PENDING || status == Order.OrderStatus.DELIVERED
                || status == Order.OrderStatus.CANCELLED) {
            Customer customer = order.getCustomerId() != null
                    ? customerRepository.findById(order.getCustomerId()).orElse(null)
                    : null;
            if (customer != null && customer.getEmail() != null && !customer.getEmail().isBlank()) {
                try {
                    emailService.sendOrderStatusEmail(
                            customer.getEmail(),
                            customer.getFullName() != null ? customer.getFullName() : order.getShippingName(),
                            order.getCode(),
                            status.name(),
                            order.getFinalAmount().doubleValue());
                } catch (Exception e) {
                    log.warn("Không gửi được email cho đơn hàng {}: {}", order.getCode(), e.getMessage());
                }
            }
            if (customer != null && customer.getUserId() != null) {
                try {
                    messagingTemplate.convertAndSend(
                            "/topic/user/" + customer.getUserId() + "/orders",
                            Map.of("type", "ORDER_STATUS_UPDATED", "orderId", order.getId(), "orderCode", order.getCode())
                    );
                } catch (Exception e) {
                    log.warn("Lỗi khi gửi websocket cho customer {}: {}", customer.getUserId(), e.getMessage());
                }
            }
        }

        return mapToResponse(orderRepository.save(order));
    }

    /**
     * Dùng riêng cho ScheduledJob: Mỗi đơn hàng là 1 transaction độc lập,
     * ngăn lỗi 1 đơn kéo theo rollback toàn bộ quá trình dọn dẹp.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void cancelOrderWithCleanup(UUID orderId, String reason, String changedBy) {
        Order order = orderRepository.findByIdWithDetails(orderId).orElseThrow();
        
        // Guard clause: Chống Race Condition giữa khách bấm hủy và job tự động hủy.
        // Xác suất thấp + Isolation Level mặc định của DB (READ COMMITTED) là đủ,
        // không cần dùng Optimistic Lock (@Version) trên Order gây phức tạp.
        if (order.getStatus() != Order.OrderStatus.PENDING 
            && order.getStatus() != Order.OrderStatus.WAITING_FOR_CONSOLIDATION 
            && order.getStatus() != Order.OrderStatus.PAYMENT_PENDING) {
            return;
        }

        doCancelOrderWithCleanup(order, reason, changedBy);
        
        order.transitionTo(Order.OrderStatus.CANCELLED, reason, changedBy);
        order.setCancelledReason(reason);
        orderRepository.save(order);
    }

    private void doCancelOrderWithCleanup(Order order, String reason, String changedBy) {
        List<InternalTransfer> transfers = transferRepository.findByReferenceOrderId(order.getId());
        Map<UUID, Integer> stuckTransferQtys = new HashMap<>();

        for (InternalTransfer transfer : transfers) {
            if (transfer.getStatus() == InternalTransfer.TransferStatus.DRAFT) {
                transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
                transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "")
                        + " | Hủy tự động do Đơn hàng " + order.getCode() + " bị hủy.");
                transferRepository.save(transfer);

                for (TransferItem tItem : transfer.getItems()) {
                    inventoryService.releaseReservation(tItem.getProductId(), transfer.getFromWarehouseId(),
                            tItem.getQuantity(), order.getId(), changedBy);
                    stuckTransferQtys.put(tItem.getProductId(),
                            stuckTransferQtys.getOrDefault(tItem.getProductId(), 0) + tItem.getQuantity());
                }
            } else if (transfer.getStatus() == InternalTransfer.TransferStatus.DISPATCHED) {
                // NV-4: Giữ nguyên Transfer DISPATCHED, chỉ detach khỏi Order.
                // Tránh cộng dồn quantity 2 lần (lúc đơn hủy và lúc xe về gọi receive).
                transfer.setReferenceOrderId(null);
                transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "")
                        + " | [CẢNH BÁO] Đơn hàng " + order.getCode() + " đã hủy nhưng hàng đang đi đường. Cần xử lý thủ công khi xe tới.");
                transferRepository.save(transfer);
                // KHÔNG giải phóng inventory ở đây, cũng KHÔNG cộng vào stuckTransferQtys
            }
        }

        if (order.getAssignedWarehouseId() != null) {
            for (OrderItem item : order.getItems()) {
                int totalRequired = item.getQuantity();
                int stuckQty = stuckTransferQtys.getOrDefault(item.getProductId(), 0);
                int qtyToReleaseAtAssigned = totalRequired - stuckQty;
                if (qtyToReleaseAtAssigned > 0) {
                    inventoryService.releaseReservation(item.getProductId(), order.getAssignedWarehouseId(),
                            qtyToReleaseAtAssigned, order.getId(), changedBy);
                }
            }
        }
    }

    @Transactional
    public void markAsPaid(UUID orderId, String gateway) {
        Order order = orderRepository.findByIdWithDetails(orderId).orElseThrow();
        if (order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
            order.setPaymentStatus(Order.PaymentStatus.PAID);
            if ("BANK_TRANSFER".equals(order.getPaymentMethod())) {
                recordBankTransferRevenue(order, "SYSTEM_" + gateway);
            }
            orderRepository.save(order);
        }
    }

    private void recordCODRevenue(Order order) {
        if (order.getAssignedWarehouseId() == null)
            return;
        cashbookRepository.save(CashbookTransaction.builder().warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.CASH_111).transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE").referenceId(order.getId()).amount(order.getFinalAmount())
                .description("Thu COD đơn hàng #" + order.getCode()).createdBy("SYSTEM").build());
    }

    @Transactional
    public OrderResponse assignWarehouse(UUID orderId, UUID newWarehouseId, String reason, String changedBy) {
        Order order = orderRepository.findByIdWithDetails(orderId).orElseThrow();
        if (order.getStatus() != Order.OrderStatus.PENDING && order.getStatus() != Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể chuyển kho khi đơn hàng đang chờ xử lý hoặc chờ gom hàng.");
        }

        Warehouse oldWarehouse = null;
        if (order.getAssignedWarehouseId() != null) {
            oldWarehouse = warehouseRepository.findById(order.getAssignedWarehouseId()).orElse(null);
        }
        Warehouse newWarehouse = warehouseRepository.findById(newWarehouseId).orElseThrow();

        // 1. Hủy các phiếu chuyển kho (DRAFT/DISPATCHED) liên quan đến đơn hàng này nếu đang gom hàng
        if (order.getStatus() == Order.OrderStatus.WAITING_FOR_CONSOLIDATION) {
            List<InternalTransfer> transfers = transferRepository.findByReferenceOrderId(orderId);
            for (InternalTransfer transfer : transfers) {
                if (transfer.getStatus() == InternalTransfer.TransferStatus.DRAFT) {
                    transfer.setStatus(InternalTransfer.TransferStatus.CANCELLED);
                    transfer.setNote((transfer.getNote() != null ? transfer.getNote() : "") + " | Hủy do Override đổi kho xử lý.");
                    transferRepository.save(transfer);
                    // Nhả tồn kho đã giữ ở kho gửi (nơi sẽ lấy hàng đi gom)
                    for (TransferItem tItem : transfer.getItems()) {
                        inventoryService.releaseReservation(tItem.getProductId(), transfer.getFromWarehouseId(), tItem.getQuantity(), orderId, changedBy);
                    }
                }
            }
        }

        // 2. Nhả tồn kho đã giữ tại kho ĐÓNG GÓI cũ
        if (order.getAssignedWarehouseId() != null) {
            for (OrderItem item : order.getItems()) {
                // TODO: Chỉ nhả số lượng thực tế đã giữ tại kho này (trừ đi phần đã bị kẹt ở các transfer đang đi đường)
                // Để đơn giản ở MVP, nhả toàn bộ số lượng yêu cầu của item
                inventoryService.releaseReservation(item.getProductId(), order.getAssignedWarehouseId(), item.getQuantity(), orderId, changedBy);
            }
        }

        // 3. Cập nhật kho mới và giữ tồn kho tại kho mới
        order.setAssignedWarehouseId(newWarehouseId);
        String note = "Điều hướng từ " + (oldWarehouse != null ? oldWarehouse.getName() : "Chưa gán") + " sang " + newWarehouse.getName();
        if (reason != null && !reason.isBlank()) {
            note += " | Lý do: " + reason;
        }

        // Giữ tồn kho ở kho mới (giả sử có đủ, nếu không đủ ở MVP sẽ âm tạm thời hoặc cần tính lại logic gom hàng)
        for (OrderItem item : order.getItems()) {
            inventoryService.reserveForOnlineOrderBatch(
                List.of(Map.of("productId", item.getProductId(), "quantity", item.getQuantity())), 
                newWarehouseId, orderId, changedBy
            );
        }

        // Tạm thời nếu Override thủ công, ta đẩy về PENDING để kho mới xử lý, hủy trạng thái WAITING_FOR_CONSOLIDATION
        order.transitionTo(Order.OrderStatus.PENDING, note, changedBy);

        return mapToResponse(orderRepository.save(order));
    }

    private void recordCashRevenue(Order order, String changedBy) {
        if (order.getAssignedWarehouseId() == null)
            return;
        cashbookRepository.save(CashbookTransaction.builder().warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.CASH_111).transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE").referenceId(order.getId()).amount(order.getFinalAmount())
                .description("Thu tiền mặt đơn hàng #" + order.getCode())
                .createdBy(changedBy != null ? changedBy : "SYSTEM").build());
    }

    private void recordBankTransferRevenue(Order order, String changedBy) {
        if (order.getAssignedWarehouseId() == null)
            return;
        cashbookRepository.save(CashbookTransaction.builder()
                .warehouseId(order.getAssignedWarehouseId())
                .fundType(CashbookTransaction.FundType.BANK_112)
                .transactionType(CashbookTransaction.TransactionType.IN)
                .referenceType("SALE_ONLINE")
                .referenceId(order.getId())
                .amount(order.getFinalAmount())
                .description("Thu chuyển khoản đơn hàng #" + order.getCode())
                .createdBy(changedBy != null ? changedBy : "SYSTEM")
                .build());
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(UUID warehouseId, Order.OrderStatus status, Order.OrderType type,
            String keyword, Instant fromDate, Instant toDate, Order.PaymentStatus paymentStatus, String paymentMethod, String provinceCode, Pageable pageable) {
        Page<Order> paged = orderRepository.searchOrders(warehouseId, status, type, keyword, fromDate, toDate, paymentStatus, paymentMethod, provinceCode, pageable);

        java.util.List<UUID> customerIds = paged.getContent().stream().map(Order::getCustomerId).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.List<UUID> warehouseIds = paged.getContent().stream().map(Order::getAssignedWarehouseId).filter(java.util.Objects::nonNull).distinct().toList();
        java.util.List<String> usernames = paged.getContent().stream().map(Order::getCreatedBy).filter(u -> u != null && !u.equals("SYSTEM")).distinct().toList();

        java.util.Map<UUID, Customer> customerMap = customerIds.isEmpty() ? java.util.Collections.emptyMap() : customerRepository.findAllById(customerIds).stream().collect(java.util.stream.Collectors.toMap(Customer::getId, c -> c));
        java.util.Map<UUID, Warehouse> warehouseMap = warehouseIds.isEmpty() ? java.util.Collections.emptyMap() : warehouseRepository.findAllById(warehouseIds).stream().collect(java.util.stream.Collectors.toMap(Warehouse::getId, w -> w));
        java.util.Map<String, User> userMap = usernames.isEmpty() ? java.util.Collections.emptyMap() : userRepository.findByUsernameIn(usernames).stream().collect(java.util.stream.Collectors.toMap(User::getUsername, u -> u));

        return paged.map(order -> {
            String custName = "Khách lẻ", custPhone = null;
            if (order.getCustomerId() != null) {
                Customer c = customerMap.get(order.getCustomerId());
                if (c != null) {
                    custName = c.getFullName();
                    custPhone = c.getPhoneNumber();
                }
            }
            String warehouseName = null;
            if (order.getAssignedWarehouseId() != null) {
                Warehouse w = warehouseMap.get(order.getAssignedWarehouseId());
                if (w != null) warehouseName = w.getName();
            }
            String createdByName = "Hệ thống";
            if (order.getCreatedBy() != null && !order.getCreatedBy().equals("SYSTEM")) {
                User u = userMap.get(order.getCreatedBy());
                if (u != null) createdByName = u.getFullName();
                else createdByName = order.getCreatedBy();
            }

            return OrderResponse.builder()
                .id(order.getId()).code(order.getCode()).customerId(order.getCustomerId())
                .customerName(custName).customerPhone(custPhone)
                .assignedWarehouseId(order.getAssignedWarehouseId()).assignedWarehouseName(warehouseName)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .type(order.getType() != null ? order.getType().name() : null)
                .shippingName(order.getShippingName()).shippingPhone(order.getShippingPhone())
                .shippingAddress(order.getShippingAddress()).provinceCode(order.getProvinceCode())
                .totalAmount(order.getTotalAmount()).shippingFee(order.getShippingFee())
                .discountAmount(order.getDiscountAmount()).finalAmount(order.getFinalAmount())
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null)
                .trackingCode(order.getTrackingCode()).shippingProvider(order.getShippingProvider())
                .codReconciled(order.getCodReconciled()).note(order.getNote())
                .cancelledReason(order.getCancelledReason())
                .packedBy(order.getPackedBy()).packedAt(order.getPackedAt())
                .createdByName(createdByName)
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt())
                .items(List.of()).statusHistory(List.of()).build();
        });
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderDetail(UUID orderId) {
        return mapToResponse(orderRepository.findByIdWithDetails(orderId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getPendingOrders(UUID warehouseId) {
        List<Order> orders = orderRepository.findPendingOrdersByWarehouse(warehouseId);
        if (orders.isEmpty()) return List.of();

        // NV-5: Bulk fetch mappings to avoid N+1 queries when mapping each order
        List<UUID> customerIds = orders.stream().map(Order::getCustomerId).filter(Objects::nonNull).distinct().toList();
        List<UUID> warehouseIds = orders.stream().map(Order::getAssignedWarehouseId).filter(Objects::nonNull).distinct().toList();
        List<String> usernames = orders.stream().map(Order::getCreatedBy).filter(u -> u != null && !u.equals("SYSTEM")).distinct().toList();

        Map<UUID, Customer> customerMap = customerIds.isEmpty() ? Collections.emptyMap() : customerRepository.findAllById(customerIds).stream().collect(Collectors.toMap(Customer::getId, c -> c));
        Map<UUID, Warehouse> warehouseMap = warehouseIds.isEmpty() ? Collections.emptyMap() : warehouseRepository.findAllById(warehouseIds).stream().collect(Collectors.toMap(Warehouse::getId, w -> w));
        Map<String, User> userMap = usernames.isEmpty() ? Collections.emptyMap() : userRepository.findByUsernameIn(usernames).stream().collect(Collectors.toMap(User::getUsername, u -> u));

        return orders.stream().map(order -> mapToSimpleResponse(order, customerMap, warehouseMap, userMap)).toList();
    }

    private String generateOrderCode() {
        return "ORD-" + System.currentTimeMillis();
    }

    public OrderResponse mapToSimpleResponse(Order order, Map<UUID, Customer> customerMap, Map<UUID, Warehouse> warehouseMap, Map<String, User> userMap) {
        String custName = "Khách lẻ", custPhone = null;
        if (order.getCustomerId() != null) {
            Customer customer = customerMap.get(order.getCustomerId());
            if (customer != null) {
                custName = customer.getFullName();
                custPhone = customer.getPhoneNumber();
            }
        }
        String warehouseName = null;
        if (order.getAssignedWarehouseId() != null) {
            Warehouse w = warehouseMap.get(order.getAssignedWarehouseId());
            if (w != null) warehouseName = w.getName();
        }
        
        String createdByName = "Hệ thống";
        if (order.getCreatedBy() != null && !order.getCreatedBy().equals("SYSTEM")) {
            User u = userMap.get(order.getCreatedBy());
            if (u != null) createdByName = u.getFullName();
            else createdByName = order.getCreatedBy();
        }

        return OrderResponse.builder()
                .id(order.getId()).code(order.getCode()).customerId(order.getCustomerId())
                .customerName(custName).customerPhone(custPhone)
                .assignedWarehouseId(order.getAssignedWarehouseId()).assignedWarehouseName(warehouseName)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .type(order.getType() != null ? order.getType().name() : null)
                .shippingName(order.getShippingName()).shippingPhone(order.getShippingPhone())
                .shippingAddress(order.getShippingAddress()).provinceCode(order.getProvinceCode())
                .totalAmount(order.getTotalAmount()).shippingFee(order.getShippingFee())
                .discountAmount(order.getDiscountAmount()).finalAmount(order.getFinalAmount())
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null)
                .trackingCode(order.getTrackingCode()).shippingProvider(order.getShippingProvider())
                .codReconciled(order.getCodReconciled()).note(order.getNote())
                .cancelledReason(order.getCancelledReason())
                .packedBy(order.getPackedBy()).packedAt(order.getPackedAt())
                .createdByName(createdByName)
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt())
                .items(List.of()).statusHistory(List.of()).build();
    }

    public OrderResponse mapToResponse(Order order) {
        String custName = "Khách lẻ", custPhone = null;
        if (order.getCustomerId() != null) {
            var customer = customerRepository.findById(order.getCustomerId()).orElse(null);
            if (customer != null) {
                custName = customer.getFullName();
                custPhone = customer.getPhoneNumber();
            }
        }
        String warehouseName = null;
        if (order.getAssignedWarehouseId() != null) {
            warehouseName = warehouseRepository.findById(order.getAssignedWarehouseId()).map(Warehouse::getName)
                    .orElse(null);
        }

        String packedByName = null;
        if (order.getPackedBy() != null) {
            packedByName = userRepository.findById(order.getPackedBy()).map(User::getFullName).orElse(null);
        }

        // NV-5: Bulk fetch products and reviews to avoid N+1 per item
        List<UUID> productIds = order.getItems() != null ? order.getItems().stream().map(OrderItem::getProductId).distinct().toList() : List.of();
        Map<UUID, Product> productMap = productIds.isEmpty() ? Collections.emptyMap() : 
                productRepository.findAllById(productIds).stream().collect(Collectors.toMap(Product::getId, p -> p));

        Set<UUID> reviewedProductIds = new HashSet<>();
        if (order.getCustomerId() != null && !productIds.isEmpty()) {
            List<ProductReview> reviews = productReviewRepository.findByProductIdInAndCustomerIdAndOrderId(productIds, order.getCustomerId(), order.getId());
            reviews.forEach(r -> reviewedProductIds.add(r.getProductId()));
        }

        List<OrderResponse.ItemResponse> items = order.getItems() == null ? List.of()
                : order.getItems().stream().map(i -> {
                    Product product = productMap.get(i.getProductId());
                    boolean isRev = reviewedProductIds.contains(i.getProductId());
                    return OrderResponse.ItemResponse.builder().productId(i.getProductId())
                            .productName(product != null ? product.getName() : null)
                            .isbnBarcode(product != null ? product.getIsbnBarcode() : null).quantity(i.getQuantity())
                            .unitPrice(i.getUnitPrice()).subtotal(i.getSubtotal())
                            .isReviewed(isRev) // <-- ĐÃ THÊM (tối ưu N+1)
                            .imageUrl(product != null ? product.getImageUrl() : null)
                            .build();
                }).toList();

        // NV-5: Bulk fetch user profiles for status history
        List<UUID> historyUserIds = order.getStatusHistory() != null ? order.getStatusHistory().stream()
                .map(h -> {
                    try { return UUID.fromString(h.getChangedBy()); } catch (Exception e) { return null; }
                }).filter(Objects::nonNull).distinct().toList() : List.of();
        Map<UUID, User> historyUserMap = historyUserIds.isEmpty() ? Collections.emptyMap() :
                userRepository.findAllById(historyUserIds).stream().collect(Collectors.toMap(User::getId, u -> u));

        List<OrderResponse.StatusHistoryResponse> history = order.getStatusHistory() == null ? List.of()
                : order.getStatusHistory().stream().map(h -> {
                    String changedByName = "Hệ thống";
                    if (h.getChangedBy() != null && !h.getChangedBy().equals("SYSTEM")) {
                        try {
                            UUID uId = UUID.fromString(h.getChangedBy());
                            changedByName = historyUserMap.containsKey(uId) ? historyUserMap.get(uId).getFullName() : h.getChangedBy();
                        } catch (Exception e) {
                            changedByName = h.getChangedBy();
                        }
                    }
                    return OrderResponse.StatusHistoryResponse.builder()
                            .oldStatus(h.getOldStatus())
                            .newStatus(h.getNewStatus())
                            .note(h.getNote())
                            .changedBy(h.getChangedBy())
                            .changedByName(changedByName)
                            .createdAt(h.getCreatedAt())
                            .build();
                }).toList();

        return OrderResponse.builder()
                .id(order.getId()).code(order.getCode()).customerId(order.getCustomerId())
                .customerName(custName).customerPhone(custPhone)
                .assignedWarehouseId(order.getAssignedWarehouseId()).assignedWarehouseName(warehouseName)
                .status(order.getStatus() != null ? order.getStatus().name() : null)
                .type(order.getType() != null ? order.getType().name() : null)
                .shippingName(order.getShippingName()).shippingPhone(order.getShippingPhone())
                .shippingAddress(order.getShippingAddress()).provinceCode(order.getProvinceCode())
                .totalAmount(order.getTotalAmount()).shippingFee(order.getShippingFee())
                .discountAmount(order.getDiscountAmount()).finalAmount(order.getFinalAmount())
                .paymentMethod(order.getPaymentMethod())
                .paymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : null)
                .trackingCode(order.getTrackingCode()).shippingProvider(order.getShippingProvider())
                .codReconciled(order.getCodReconciled()).note(order.getNote())
                .cancelledReason(order.getCancelledReason())
                .packedBy(order.getPackedBy())
                .packedByName(packedByName)
                .packedAt(order.getPackedAt())
                .createdByName(order.getCreatedBy() != null && !order.getCreatedBy().equals("SYSTEM")
                        ? userRepository.findByUsername(order.getCreatedBy()).map(User::getFullName)
                                .orElse(order.getCreatedBy())
                        : "Hệ thống")
                .createdAt(order.getCreatedAt()).updatedAt(order.getUpdatedAt())
                .items(items).statusHistory(history).build();
    }

    public Map<String, Object> getOrderStats(UUID warehouseId, Order.OrderStatus status, Order.OrderType type, String keyword, Instant fromDate, Instant toDate, Order.PaymentStatus paymentStatus, String paymentMethod, String provinceCode, String source) {
        long totalCount = 0;
        long pendingCount = 0;
        long paidCount = 0;
        double totalRevenue = 0.0;

        if ("ONLINE".equals(source) || "ALL".equals(source) || source == null) {
            Map<String, Object> oStats = orderRepository.getOrderStats(
                warehouseId, 
                status, 
                type, 
                keyword,
                fromDate,
                toDate,
                paymentStatus,
                paymentMethod,
                provinceCode,
                Order.OrderStatus.PENDING,
                Order.PaymentStatus.PAID,
                Order.OrderStatus.CANCELLED
            );
            if (oStats != null) {
                totalCount += ((Number) oStats.getOrDefault("totalCount", 0)).longValue();
                pendingCount += ((Number) oStats.getOrDefault("pendingCount", 0)).longValue();
                paidCount += ((Number) oStats.getOrDefault("paidCount", 0)).longValue();
                totalRevenue += ((Number) oStats.getOrDefault("totalRevenue", 0)).doubleValue();
            }
        }

        if ("OFFLINE".equals(source) || "ALL".equals(source) || source == null) {
            String invType = null;
            if (status != null) {
                if (status == Order.OrderStatus.DELIVERED) invType = "SALE";
                else if (status == Order.OrderStatus.RETURNED) invType = "RETURN";
                else if (status == Order.OrderStatus.CANCELLED) invType = "VOIDED";
                else {
                    invType = "NONE";
                }
            }
            if (!"NONE".equals(invType)) {
                Map<String, Object> iStats = invoiceRepository.getInvoiceStats(warehouseId, invType, keyword, fromDate, toDate, null);
                if (iStats != null) {
                    totalCount += ((Number) iStats.getOrDefault("totalCount", 0)).longValue();
                    pendingCount += ((Number) iStats.getOrDefault("pendingCount", 0)).longValue();
                    paidCount += ((Number) iStats.getOrDefault("paidCount", 0)).longValue();
                    totalRevenue += ((Number) iStats.getOrDefault("totalRevenue", 0)).doubleValue();
                }
            }
        }

        return Map.of(
            "totalCount", totalCount,
            "pendingCount", pendingCount,
            "paidCount", paidCount,
            "totalRevenue", totalRevenue
        );
    }
}