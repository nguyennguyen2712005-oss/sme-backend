package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.config.AppProperties;
import sme.backend.dto.request.AdjustInventoryRequest;
import sme.backend.dto.response.InventoryResponse;
import sme.backend.dto.response.InventoryTransactionResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository txnRepository;
    private final ProductRepository productRepository;
    private final NotificationService notificationService;
    private final AppProperties appProperties;
    private final OrderRepository orderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final InternalTransferRepository transferRepository;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private InventoryService self;

    private int getSafeLowStockThreshold() {
        try {
            if (appProperties != null && appProperties.getBusiness() != null) {
                return appProperties.getBusiness().getLowStockThreshold();
            }
        } catch (Exception ignored) {
        }
        return 10;
    }

    @Transactional
    public Inventory getOrCreate(UUID productId, UUID warehouseId) {
        return inventoryRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseGet(() -> {
                    Inventory inv = Inventory.builder()
                            .productId(productId)
                            .warehouseId(warehouseId)
                            .quantity(0)
                            .reservedQuantity(0)
                            .inTransit(0)
                            .minQuantity(getSafeLowStockThreshold())
                            .build();
                    return inventoryRepository.save(inv);
                });
    }

    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void importStock(UUID productId, UUID warehouseId,
            int quantity, java.math.BigDecimal importPrice,
            UUID referenceId, String operator) {
        if (warehouseId == null) {
            throw new BusinessException("INVALID_WAREHOUSE", "Không thể nhập kho vì thiếu thông tin chi nhánh.");
        }
        try {
            getOrCreate(productId, warehouseId);
            // NV-2: Dùng Pessimistic Lock (timeout 10s) cho Admin task, ưu tiên POS (3s)
            Inventory inv = inventoryRepository.findByProductAndWarehouseWithAdminLock(productId, warehouseId)
                    .orElseThrow(() -> new ResourceNotFoundException("Inventory", productId));
            if (inv.getMinQuantity() == null) {
                inv.setMinQuantity(getSafeLowStockThreshold());
            }
            int before = inv.getQuantity() != null ? inv.getQuantity() : 0;
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

            java.math.BigDecimal safeImportPrice = importPrice != null ? importPrice : java.math.BigDecimal.ZERO;
            try {
                product.recalculateMAC(before, quantity, safeImportPrice);
                productRepository.save(product);
            } catch (Exception e) {
                log.warn("Lỗi tính MAC: {}", e.getMessage());
            }

            inv.addQuantity(quantity);
            inv = inventoryRepository.save(inv);
            recordTransaction(inv, referenceId, "IMPORT", quantity, before, inv.getQuantity(), operator, null);
            checkLowStockAlert(inv, before);
        } catch (Exception e) {
            throw new BusinessException("IMPORT_STOCK_CRASH", "Lỗi nhập kho: " + e.getMessage());
        }
    }

    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void deductForPOSSale(UUID productId, UUID warehouseId,
            int quantity, UUID invoiceId, String operator) {
        Inventory inv = inventoryRepository.findByProductAndWarehouseWithLock(productId, warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tồn kho"));

        if (inv.getMinQuantity() == null)
            inv.setMinQuantity(getSafeLowStockThreshold());

        int before = inv.getQuantity() != null ? inv.getQuantity() : 0;
        inv.deductPhysicalQuantity(quantity);
        inv = inventoryRepository.save(inv);
        recordTransaction(inv, invoiceId, "SALE_POS", -quantity, before, inv.getQuantity(), operator, null);
        checkLowStockAlert(inv, before);
    }

    /**
     * DEDUCT HÀNG LOẠT CHO POS (ANTI-DEADLOCK)
     */
    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void deductForPOSSaleBatch(List<sme.backend.dto.request.CreateOrderRequest.OrderItemRequest> items, UUID warehouseId, UUID invoiceId, String operator) {
        // Sắp xếp tăng dần theo ProductID để tránh Cyclic Deadlock
        List<sme.backend.dto.request.CreateOrderRequest.OrderItemRequest> sortedItems = items.stream()
                .sorted((a, b) -> a.getProductId().compareTo(b.getProductId()))
                .collect(java.util.stream.Collectors.toList());

        for (sme.backend.dto.request.CreateOrderRequest.OrderItemRequest item : sortedItems) {
            deductForPOSSale(item.getProductId(), warehouseId, item.getQuantity(), invoiceId, operator);
        }
    }

    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void returnToStock(UUID productId, UUID warehouseId,
            int quantity, UUID referenceId, String reason, String operator) {

        getOrCreate(productId, warehouseId);
        // NV-2: Dùng Pessimistic Lock (timeout 10s) cho Admin task, ưu tiên POS (3s)
        Inventory inv = inventoryRepository.findByProductAndWarehouseWithAdminLock(productId, warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", productId));
        int before = inv.getQuantity() != null ? inv.getQuantity() : 0;

        boolean isReturnToSellable = "STOCK".equals(reason) || "RETURNED_ORDER".equals(reason) || "VOID_INVOICE".equals(reason);
        String txnType = isReturnToSellable ? "RETURN_TO_STOCK" : "RETURN_TO_DEFECT";

        if (isReturnToSellable) {
            inv.addQuantity(quantity);
        }

        inv = inventoryRepository.save(inv);

        recordTransaction(
                inv,
                referenceId,
                txnType,
                isReturnToSellable ? quantity : 0,
                before,
                inv.getQuantity(),
                operator,
                "Hoàn trả hàng hóa - Lý do: " + reason);
    }

    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void reserveForOnlineOrder(UUID productId, UUID warehouseId, int quantity, UUID orderId, String operator) {
        Inventory inv = inventoryRepository.findByProductAndWarehouseWithLock(productId, warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tồn kho"));
        int beforeAvailable = inv.getAvailableQuantity();
        inv.reserveQuantity(quantity);
        inv = inventoryRepository.save(inv);
        recordTransaction(inv, orderId, "RESERVE", 0, beforeAvailable, inv.getAvailableQuantity(),
                operator, null);
        checkLowStockAlert(inv, beforeAvailable);
    }

    /**
     * KHÓA TỒN KHO HÀNG LOẠT (ANTI-DEADLOCK)
     * Đây là hàm tầng thấp nhất để các Service khác gọi trước khi xử lý logic (POS, Order).
     * Sắp xếp UUID trước khi khóa để triệt tiêu Cyclic Deadlock.
     */
    @Transactional
    public void lockInventoriesForTransaction(List<UUID> productIds, UUID warehouseId) {
        List<UUID> sortedIds = productIds.stream()
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());

        for (UUID pid : sortedIds) {
            // Đảm bảo row tồn tại trong db trước khi lock, tránh lỗi 404
            getOrCreate(pid, warehouseId);
            inventoryRepository.findByProductAndWarehouseWithLock(pid, warehouseId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tồn kho cho SP: " + pid));
        }
    }

    /**
     * RESERVE HÀNG LOẠT CHO ONLINE (ANTI-DEADLOCK)
     */
    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void reserveForOnlineOrderBatch(List<java.util.Map<String, Object>> items, UUID warehouseId, UUID orderId, String operator) {
        List<UUID> productIds = items.stream()
                .map(item -> (UUID) item.get("productId"))
                .collect(java.util.stream.Collectors.toList());
        
        lockInventoriesForTransaction(productIds, warehouseId);

        for (java.util.Map<String, Object> item : items) {
            UUID productId = (UUID) item.get("productId");
            int quantity = (Integer) item.get("quantity");
            
            // Không sợ deadlock nữa vì đã được khóa ở trên
            Inventory inv = inventoryRepository.findByProductIdAndWarehouseId(productId, warehouseId).orElseThrow();
            int beforeAvailable = inv.getAvailableQuantity();
            inv.reserveQuantity(quantity);
            inv = inventoryRepository.save(inv);
            recordTransaction(inv, orderId, "RESERVE", 0, beforeAvailable, inv.getAvailableQuantity(), operator, null);
            checkLowStockAlert(inv, beforeAvailable);
        }
    }

    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void confirmOnlineShipment(UUID productId, UUID warehouseId, int quantity, UUID orderId, String operator) {
        Inventory inv = inventoryRepository.findByProductAndWarehouseWithLock(productId, warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tồn kho"));
        if (inv.getMinQuantity() == null)
            inv.setMinQuantity(getSafeLowStockThreshold());

        int before = inv.getQuantity() != null ? inv.getQuantity() : 0;
        inv.confirmShipment(quantity);
        inv = inventoryRepository.save(inv);
        recordTransaction(inv, orderId, "SALE_ONLINE", -quantity, before, inv.getQuantity(), operator, null);
        checkLowStockAlert(inv, before);
    }

    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void adjustInventory(AdjustInventoryRequest req, UUID referenceId, String operator) {
        getOrCreate(req.getProductId(), req.getWarehouseId());
        // NV-2: Dùng Pessimistic Lock (timeout 10s) cho Admin task, ưu tiên POS (3s)
        Inventory inv = inventoryRepository.findByProductAndWarehouseWithAdminLock(req.getProductId(), req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", req.getProductId()));
        int before = inv.getQuantity() != null ? inv.getQuantity() : 0;
        int diff = req.getActualQuantity() - before;

        inv.setQuantity(req.getActualQuantity());
        inv = inventoryRepository.save(inv);

        recordTransaction(inv, referenceId, "ADJUSTMENT", diff, before, req.getActualQuantity(), operator,
                req.getReason());
        checkLowStockAlert(inv, before);
    }

    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void releaseReservation(UUID productId, UUID warehouseId, int quantity, UUID orderId, String operator) {
        inventoryRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .ifPresent(inv -> {
                    inv.releaseReservedQuantity(quantity);
                    Inventory savedInv = inventoryRepository.save(inv);
                    recordTransaction(savedInv, orderId, "RELEASE", 0, savedInv.getAvailableQuantity() - quantity,
                            savedInv.getAvailableQuantity(), operator, null);
                });
    }

    @Cacheable(value = "inventories", key = "#productId + '_' + #warehouseId")
    @Transactional(readOnly = true)
    public Inventory getInventory(UUID productId, UUID warehouseId) {
        return inventoryRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tồn kho"));
    }

    @Transactional(readOnly = true)
    public List<Inventory> getLowStockAlerts(UUID warehouseId) {
        return inventoryRepository.findLowStockByWarehouse(warehouseId);
    }

    public void recordTransaction(Inventory inv, UUID referenceId, String type,
            int change, int before, int after, String operator, String note) {

        String safeOperator = (operator != null && operator.length() > 90) ? operator.substring(0, 90) : operator;
        if (safeOperator == null)
            safeOperator = "SYSTEM";

        InventoryTransaction txn = InventoryTransaction.builder()
                .inventoryId(inv.getId())
                .referenceId(referenceId != null ? referenceId : UUID.randomUUID())
                .transactionType(type)
                .quantityChange(change)
                .quantityBefore(before)
                .quantityAfter(after)
                .note(note)
                .createdBy(safeOperator)
                .build();
        txnRepository.save(txn);
    }

    private void checkLowStockAlert(Inventory inv, int beforeAvailable) {
        try {
            int minQty = inv.getMinQuantity() != null ? inv.getMinQuantity() : getSafeLowStockThreshold();
            int currentAvailable = inv.getAvailableQuantity();
            
            boolean droppedBelowThreshold = beforeAvailable >= minQty && currentAvailable < minQty;
            boolean newlyImportedLowStock = beforeAvailable == 0 && currentAvailable > 0 && currentAvailable < minQty;
            
            if (droppedBelowThreshold || newlyImportedLowStock) {
                notificationService.notifyLowStock(inv);
            }
        } catch (Exception ignored) {
        }
    }

    // ====================================================================================
    // KHÔI PHỤC LẠI NGUYÊN BẢN: Cập nhật dựa trên inventoryId
    // ====================================================================================
    @Transactional
    @CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public void updateMinQuantity(UUID inventoryId, int newMinQuantity, String operator) {
        if (newMinQuantity < 0) {
            throw new BusinessException("INVALID_MIN_QTY", "Định mức tồn kho tối thiểu không được nhỏ hơn 0");
        }

        Inventory inv = inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", inventoryId));

        int oldMin = inv.getMinQuantity() != null ? inv.getMinQuantity() : 0;
        inv.setMinQuantity(newMinQuantity);
        inventoryRepository.save(inv);
        if (inv.getAvailableQuantity() < newMinQuantity && inv.getAvailableQuantity() >= oldMin) {
            notificationService.notifyLowStock(inv);
        }
        log.info("User {} updated minQuantity for Inventory {} from {} to {}", operator, inventoryId, oldMin,
                newMinQuantity);
    }

    @Transactional(readOnly = true)
    public Page<InventoryResponse> searchInventory(UUID warehouseId, String keyword, UUID categoryId, String status,
            Pageable pageable) {
        return inventoryRepository.searchInventoryWithProductDetails(warehouseId, keyword, categoryId, status,
                pageable);
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventoryByProduct(UUID warehouseId, UUID productId) {
        return inventoryRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .map(inv -> {
                    Product p = productRepository.findById(productId).orElseThrow();
                    String catName = p.getCategoryId() != null ? "Có danh mục" : "";
                    String warehouseName = ""; // Or fetch it if needed, but this method is getInventoryByProduct (often used where warehouse is known)
                    return new InventoryResponse(
                            inv.getId(), p.getId(), p.getName(), p.getSku(), p.getIsbnBarcode(), p.getImageUrl(),
                            catName, warehouseName,
                            inv.getQuantity(), inv.getReservedQuantity(), inv.getInTransit(), inv.getMinQuantity(),
                            inv.isLowStock());
                })
                .orElseGet(() -> {
                    Product p = productRepository.findById(productId)
                            .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
                    return new InventoryResponse(
                            null, p.getId(), p.getName(), p.getSku(), p.getIsbnBarcode(), p.getImageUrl(), "", "",
                            0, 0, 0, 0, false);
                });
    }

    @Transactional(readOnly = true)
    public Page<InventoryTransactionResponse> getTransactionsWithReferenceCode(UUID inventoryId, Pageable pageable) {
        Page<InventoryTransaction> txns = txnRepository.findByInventoryIdOrderByCreatedAtDesc(inventoryId, pageable);

        return txns.map(txn -> {
            InventoryTransactionResponse dto = new InventoryTransactionResponse();
            dto.setId(txn.getId());
            dto.setTransactionType(txn.getTransactionType());
            dto.setQuantityChange(txn.getQuantityChange());
            dto.setQuantityBefore(txn.getQuantityBefore());
            dto.setQuantityAfter(txn.getQuantityAfter());
            dto.setNote(txn.getNote());
            dto.setCreatedAt(txn.getCreatedAt());
            dto.setCreatedBy(resolveCreatedBy(txn.getCreatedBy()));

            if (txn.getReferenceId() != null) {
                try {
                    switch (txn.getTransactionType()) {
                        case "SALE_POS":
                        case "RETURN_TO_STOCK":
                        case "RETURN_TO_DEFECT":
                            invoiceRepository.findById(txn.getReferenceId())
                                    .ifPresent(i -> dto.setReferenceCode(i.getCode()));
                            break;
                        case "SALE_ONLINE":
                        case "RESERVE":
                        case "RELEASE":
                            orderRepository.findById(txn.getReferenceId())
                                    .ifPresent(o -> dto.setReferenceCode(o.getCode()));
                            break;
                        case "IMPORT":
                            purchaseOrderRepository.findById(txn.getReferenceId())
                                    .ifPresent(po -> dto.setReferenceCode(po.getCode()));
                            break;
                        case "TRANSFER_OUT":
                        case "TRANSFER_IN":
                            transferRepository.findById(txn.getReferenceId())
                                    .ifPresent(tr -> dto.setReferenceCode(tr.getCode()));
                            break;
                        default:
                            dto.setReferenceCode("-");
                    }
                } catch (Exception e) {
                    dto.setReferenceCode("Lỗi tra cứu");
                }
            } else {
                dto.setReferenceCode(txn.getTransactionType().equals("ADJUSTMENT") ? "Kiểm kê" : "-");
            }
            if (dto.getReferenceCode() == null)
                dto.setReferenceCode("-");
            return dto;
        });
    }

    @Transactional(readOnly = true)
    public Page<InventoryTransactionRepository.TransactionProjection> searchGlobalTransactions(
            UUID warehouseId, String transactionType, String keyword,
            java.time.Instant fromDate, java.time.Instant toDate, Pageable pageable) {
        return txnRepository.searchGlobalTransactions(warehouseId, transactionType, keyword, fromDate, toDate,
                pageable);
    }

    // CẢNH BÁO: Vì có @Cacheable, nếu gọi method này trong một Transaction đang mở (VD: OrderService)
    // thì Spring có thể trả về dữ liệu cache cũ trước khi Transaction commit.
    // Thường được dùng chủ yếu cho mục đích đọc hiển thị UI.
    @Cacheable(value = "inventory_by_product", key = "#productId.toString()")
    @Transactional(readOnly = true)
    public List<sme.backend.dto.response.WarehouseInventoryItem> getInventoryAcrossWarehouses(UUID productId) {
        List<sme.backend.repository.WarehouseInventoryProjection> projections = inventoryRepository.findInventoryForAllWarehouses(productId);
        int safeLowStock = getSafeLowStockThreshold();
        
        return projections.stream().map(proj -> {
            int qty = proj.getQuantity() != null ? proj.getQuantity() : 0;
            int resQty = proj.getReservedQuantity() != null ? proj.getReservedQuantity() : 0;
            int minQty = proj.getMinQuantity() != null ? proj.getMinQuantity() : safeLowStock;
            int availableQty = qty - resQty;
            boolean lowStock = minQty > 0 && qty > 0 && qty <= minQty;
            
            return new sme.backend.dto.response.WarehouseInventoryItem(
                proj.getWarehouseId(),
                proj.getWarehouseName(),
                proj.getInventoryId(),
                qty,
                resQty,
                proj.getInTransit() != null ? proj.getInTransit() : 0,
                availableQty,
                minQty,
                lowStock
            );
        }).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void adjustInventorySingleItem(AdjustInventoryRequest req, UUID referenceId, String operator) {
        // NV-2: Dùng Pessimistic Lock (timeout 10s) cho Admin task, ưu tiên POS (3s)
        // Chúng ta dùng findByProductAndWarehouseWithAdminLock
        Inventory inv = inventoryRepository.findByProductAndWarehouseWithAdminLock(req.getProductId(), req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory", req.getProductId()));
        int before = inv.getQuantity() != null ? inv.getQuantity() : 0;
        int diff = req.getActualQuantity() - before;

        inv.setQuantity(req.getActualQuantity());
        inv = inventoryRepository.save(inv);

        recordTransaction(inv, referenceId, "ADJUSTMENT", diff, before, req.getActualQuantity(), operator,
                req.getReason());
        checkLowStockAlert(inv, before);
    }

    @org.springframework.cache.annotation.CacheEvict(value = {"inventories", "inventory_by_product"}, allEntries = true)
    public sme.backend.dto.response.BulkAdjustResponse adjustInventoryBatch(List<AdjustInventoryRequest> requests, String operator) {
        UUID referenceId = UUID.randomUUID();
        int successCount = 0;
        int errorCount = 0;
        List<sme.backend.dto.response.BulkAdjustResponse.AdjustError> errors = new java.util.ArrayList<>();

        // Sắp xếp requests theo productId để giảm thiểu deadlock (giống lockInventoriesForTransaction)
        List<AdjustInventoryRequest> sortedRequests = new java.util.ArrayList<>(requests);
        sortedRequests.sort((a, b) -> a.getProductId().compareTo(b.getProductId()));

        for (int i = 0; i < sortedRequests.size(); i++) {
            AdjustInventoryRequest req = sortedRequests.get(i);
            try {
                // Đảm bảo record tồn tại trước khi lock
                getOrCreate(req.getProductId(), req.getWarehouseId());
                self.adjustInventorySingleItem(req, referenceId, operator);
                successCount++;
            } catch (jakarta.persistence.PessimisticLockException | org.hibernate.exception.LockAcquisitionException e) {
                // PessimisticLockException được phân loại retriable: true khác với các lỗi business logic khác
                // vì đây là lỗi tạm thời khi đụng độ khóa ở POS/Online Order, không phải do dữ liệu sai.
                errorCount++;
                errors.add(new sme.backend.dto.response.BulkAdjustResponse.AdjustError(
                        i + 1, "Lock timeout - vui lòng thử lại", true
                ));
            } catch (Exception e) {
                // Các lỗi business logic hoặc dữ liệu sai (ví dụ NotFound) thì không thể tự sửa bằng cách thử lại.
                errorCount++;
                errors.add(new sme.backend.dto.response.BulkAdjustResponse.AdjustError(
                        i + 1, e.getMessage(), false
                ));
            }
        }

        return sme.backend.dto.response.BulkAdjustResponse.builder()
                .successCount(successCount)
                .errorCount(errorCount)
                .errors(errors)
                .build();
    }

    // Nếu createdBy là UUID (dữ liệu cũ) → resolve về tên người dùng
    private String resolveCreatedBy(String createdBy) {
        if (createdBy == null || createdBy.isBlank()) return createdBy;
        try {
            UUID userId = UUID.fromString(createdBy);
            return userRepository.findById(userId)
                    .map(u -> u.getFullName() != null && !u.getFullName().isBlank()
                            ? u.getFullName() : u.getUsername())
                    .orElse(createdBy);
        } catch (IllegalArgumentException e) {
            return createdBy; // Đã là tên hoặc "SYSTEM" → giữ nguyên
        }
    }
}