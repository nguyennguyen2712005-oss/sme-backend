package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.config.AppProperties;
import sme.backend.dto.request.CheckoutRequest;
import sme.backend.dto.response.InvoiceResponse;
import sme.backend.dto.response.ShiftResponse;
import sme.backend.entity.*;
import sme.backend.exception.BusinessException;
import sme.backend.exception.InsufficientStockException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class POSService {

        private final ShiftService shiftService;
        private final InventoryService inventoryService;
        private final InventoryRepository inventoryRepository;
        private final InventoryTransactionRepository inventoryTransactionRepository;
        private final InvoiceRepository invoiceRepository;
        private final CustomerRepository customerRepository;
        private final ProductRepository productRepository;
        private final CashbookTransactionRepository cashbookRepository;
        private final WarehouseRepository warehouseRepository;
        private final UserRepository userRepository;
        private final PromotionService promotionService;
        private final AppProperties appProperties;
        private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

        @jakarta.annotation.PostConstruct
        public void fixDbConstraints() {
                try {
                        jdbcTemplate.execute("ALTER TABLE invoices DROP CONSTRAINT IF EXISTS invoices_type_check;");
                        jdbcTemplate.execute("ALTER TABLE invoice_payments DROP CONSTRAINT IF EXISTS invoice_payments_method_check;");
                        log.info("Đã xóa constraint cũ thành công để hỗ trợ giá trị Enum mới.");
                } catch (Exception e) {
                        log.warn("Không thể xóa constraint: {}", e.getMessage());
                }
        }

        // ─────────────────────────────────────────────────────────
        // CHECKOUT (POS-04, POS-05) — toàn bộ là 1 transaction ACID
        // ─────────────────────────────────────────────────────────
        @Transactional
        public InvoiceResponse checkout(CheckoutRequest req, UUID cashierId, UUID warehouseId) {

                // 1. Validate shift đang OPEN
                Shift shift = shiftService.getOpenShiftByCashier(cashierId);
                if (!shift.getId().equals(req.getShiftId())) {
                        throw new BusinessException("SHIFT_MISMATCH",
                                        "shiftId không khớp với ca làm việc đang mở");
                }

                // 2. Load customer (optional)
                Customer customer = null;
                if (req.getCustomerId() != null) {
                        customer = customerRepository.findById(req.getCustomerId())
                                        .orElseThrow(() -> new ResourceNotFoundException("Customer",
                                                        req.getCustomerId()));
                }

                // 3. Build invoice
                String code = generateInvoiceCode();
                Invoice invoice = Invoice.builder()
                                .code(code)
                                .shiftId(shift.getId())
                                .warehouseId(warehouseId)
                                .customerId(customer != null ? customer.getId() : null)
                                .type(Invoice.InvoiceType.SALE)
                                .cashierId(cashierId)
                                .note(req.getNote())
                                .build();

                // 4. Xử lý từng sản phẩm: validate tồn kho + tạo InvoiceItem
                BigDecimal totalAmount = BigDecimal.ZERO;

                // Lock toàn bộ inventory theo đúng thứ tự ProductId để tránh Cyclic Deadlock
                List<UUID> productIds = req.getItems().stream()
                                .map(CheckoutRequest.CartItemRequest::getProductId)
                                .collect(java.util.stream.Collectors.toList());
                inventoryService.lockInventoriesForTransaction(productIds, warehouseId);

                // Validate tồn kho trước – không trừ vội, tránh partial rollback
                for (CheckoutRequest.CartItemRequest cartItem : req.getItems()) {
                        Product product = productRepository.findById(cartItem.getProductId())
                                        .orElseThrow(() -> new ResourceNotFoundException(
                                                        "Product", cartItem.getProductId()));

                        Inventory inv = inventoryRepository
                                        .findByProductIdAndWarehouseId(cartItem.getProductId(), warehouseId)
                                        .orElse(null);

                        if (inv == null || inv.getAvailableQuantity() < cartItem.getQuantity()) {
                                int available = inv != null ? inv.getAvailableQuantity() : 0;
                                throw new InsufficientStockException(
                                                String.format("Sản phẩm '%s' không đủ hàng. Khả dụng: %d, Yêu cầu: %d",
                                                                product.getName(), available, cartItem.getQuantity()));
                        }
                }

                String cashierName = userRepository.findById(cashierId)
                        .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                        .orElse("POS");

                for (CheckoutRequest.CartItemRequest cartItem : req.getItems()) {
                        Product product = productRepository.findById(cartItem.getProductId()).orElseThrow();
                        Inventory inv = inventoryRepository
                                        .findByProductIdAndWarehouseId(product.getId(), warehouseId)
                                        .orElseThrow(); // Đã check ở trên nên chắc chắn có nếu flow đúng

                        int before = inv.getQuantity();
                        inv.deductPhysicalQuantity(cartItem.getQuantity());
                        inventoryRepository.save(inv);

                        // Thẻ kho
                        inventoryTransactionRepository.save(
                                        InventoryTransaction.builder()
                                                        .inventoryId(inv.getId())
                                                        .referenceId(UUID.randomUUID()) // updated post-save normally
                                                        .transactionType("SALE_POS")
                                                        .quantityChange(-cartItem.getQuantity())
                                                        .quantityBefore(before)
                                                        .quantityAfter(inv.getQuantity())
                                                        .createdBy(cashierName)
                                                        .build());

                        InvoiceItem item = InvoiceItem.builder()
                                        .productId(product.getId())
                                        .quantity(cartItem.getQuantity())
                                        .unitPrice(cartItem.getUnitPrice())
                                        .macPrice(product.getMacPrice())
                                        .subtotal(cartItem.getUnitPrice()
                                                        .multiply(BigDecimal.valueOf(cartItem.getQuantity())))
                                        .build();
                        invoice.addItem(item);
                        // NV-2: Dùng native query để update soldQuantity nguyên tử, tránh Lost Update và OptimisticLockException
                        productRepository.incrementSoldQuantity(product.getId(), cartItem.getQuantity());
                        totalAmount = totalAmount.add(item.getSubtotal());
                }

                // 5. Discount
                BigDecimal discountAmount = BigDecimal.ZERO;

                if (req.getOrderDiscountAmt() != null) {
                        discountAmount = discountAmount.add(req.getOrderDiscountAmt());
                }
                if (req.getCouponDiscountAmt() != null) {
                        discountAmount = discountAmount.add(req.getCouponDiscountAmt());
                }

                int pointsToUse = req.getPointsToUse() != null ? req.getPointsToUse() : 0;
                if (pointsToUse > 0 && customer != null) {
                        if (pointsToUse % 500 != 0) {
                                throw new BusinessException("INVALID_POINTS",
                                                "Chỉ có thể quy đổi điểm theo mốc voucher 500 điểm.");
                        }
                        customer.deductPoints(pointsToUse);
                        BigDecimal pointsDiscount = BigDecimal.valueOf(pointsToUse)
                                        .multiply(BigDecimal.valueOf(
                                                        appProperties.getBusiness().getLoyaltyPointsRedeemValue()));
                        discountAmount = discountAmount.add(pointsDiscount);
                }

                if (discountAmount.compareTo(totalAmount) > 0)
                        discountAmount = totalAmount;

                BigDecimal finalAmount = totalAmount.subtract(discountAmount);
                invoice.setTotalAmount(totalAmount);
                invoice.setDiscountAmount(discountAmount);
                invoice.setFinalAmount(finalAmount);

                // 6. Payment validation
                BigDecimal totalPaid = req.getPayments().stream()
                                .map(CheckoutRequest.PaymentRequest::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                // Removed INSUFFICIENT_PAYMENT check to allow unpaid or partially paid invoices
                // (Debt sales)

                // 7. Payments + Cashbook
                for (CheckoutRequest.PaymentRequest p : req.getPayments()) {
                        InvoicePayment.PaymentMethod method = InvoicePayment.PaymentMethod
                                        .valueOf(p.getMethod().toUpperCase());
                        invoice.addPayment(InvoicePayment.builder()
                                        .method(method).amount(p.getAmount()).reference(p.getReference()).build());
                        recordCashbook(shift, method, p.getAmount(), warehouseId);
                }

                // 8. Points
                int pointsEarned = 0;
                if (customer != null) {
                        pointsEarned = finalAmount.divide(
                                        BigDecimal.valueOf(appProperties.getBusiness().getLoyaltyPointsPerVnd()),
                                        0, RoundingMode.DOWN).intValue();
                        customer.addPoints(pointsEarned);
                        customer.setTotalSpent(customer.getTotalSpent().add(finalAmount));
                        customerRepository.save(customer);
                }
                invoice.setPointsUsed(pointsToUse);
                invoice.setPointsEarned(pointsEarned);

                // 9. Save
                invoice = invoiceRepository.save(invoice);

                // 10. Update coupon usage
                if (req.getCouponCodes() != null && !req.getCouponCodes().isEmpty()) {
                        for (String promoCode : req.getCouponCodes()) {
                                if (promoCode != null && !promoCode.trim().isEmpty()) {
                                        promotionService.markUsed(promoCode.trim());
                                }
                        }
                }

                log.info("Checkout completed: invoice={}, amount={}, cashier={}", invoice.getCode(), finalAmount,
                                cashierId);

                return buildInvoiceResponse(invoice);
        }

        @Transactional
        public InvoiceResponse refund(UUID originalInvoiceId, UUID shiftId, List<RefundItem> items,
                        String returnDestination, UUID cashierId, UUID warehouseId, String note) {
                Invoice original = invoiceRepository.findByIdWithDetails(originalInvoiceId)
                                .orElseThrow(() -> new ResourceNotFoundException("Invoice", originalInvoiceId));
                if (original.getType() != Invoice.InvoiceType.SALE)
                        throw new BusinessException("INVALID_REFUND", "Không thể trả hàng cho hóa đơn trả hàng");

                Invoice returnInvoice = Invoice.builder().code(generateReturnCode()).shiftId(shiftId)
                                .customerId(original.getCustomerId()).type(Invoice.InvoiceType.RETURN)
                                .cashierId(cashierId)
                                .returnOfId(original.getId()).note(note).build();
                BigDecimal totalRefund = BigDecimal.ZERO;

                for (RefundItem ri : items) {
                        InvoiceItem originalItem = original.getItems().stream()
                                        .filter(i -> i.getProductId().equals(ri.productId()))
                                        .findFirst()
                                        .orElseThrow(() -> new BusinessException("PRODUCT_NOT_IN_INVOICE",
                                                        "Sản phẩm không có trong hóa đơn gốc: " + ri.productId()));

                        int alreadyReturned = originalItem.getReturnedQuantity() != null
                                        ? originalItem.getReturnedQuantity()
                                        : 0;
                        int remaining = originalItem.getQuantity() - alreadyReturned;

                        if (ri.quantity() > remaining) {
                                throw new BusinessException("EXCESSIVE_RETURN",
                                                String.format("Sản phẩm [%s] chỉ còn %d cái có thể trả (Đã mua %d, đã trả %d)",
                                                                originalItem.getProductId(), remaining,
                                                                originalItem.getQuantity(), alreadyReturned));
                        }

                        // Update original item
                        originalItem.setReturnedQuantity(alreadyReturned + ri.quantity());

                        returnInvoice.addItem(InvoiceItem.builder()
                                        .productId(ri.productId())
                                        .quantity(-ri.quantity())
                                        .unitPrice(originalItem.getUnitPrice())
                                        .macPrice(originalItem.getMacPrice())
                                        .subtotal(originalItem.getUnitPrice()
                                                        .multiply(BigDecimal.valueOf(-ri.quantity())))
                                        .build());

                        totalRefund = totalRefund
                                        .add(originalItem.getUnitPrice().multiply(BigDecimal.valueOf(ri.quantity())));

                        inventoryService.returnToStock(ri.productId(), warehouseId, ri.quantity(), null,
                                        returnDestination,
                                        cashierId.toString());

                        // NV-2: Dùng native query decrement an toàn
                        productRepository.decrementSoldQuantity(ri.productId(), ri.quantity());
                }

                returnInvoice.setTotalAmount(totalRefund);
                returnInvoice.setFinalAmount(totalRefund);
                Shift shift = shiftService.getOpenShiftByCashier(cashierId);
                cashbookRepository.save(CashbookTransaction.builder().warehouseId(warehouseId).shiftId(shift.getId())
                                .fundType(CashbookTransaction.FundType.CASH_111)
                                .transactionType(CashbookTransaction.TransactionType.OUT).referenceType("INVOICE")
                                .amount(totalRefund)
                                .description("Trả hàng hóa đơn #" + original.getCode()).createdBy(cashierId.toString())
                                .build());
                returnInvoice = invoiceRepository.save(returnInvoice);
                return buildInvoiceResponse(returnInvoice);
        }

        @Transactional(readOnly = true)
        public InvoiceResponse getInvoice(UUID id) {
                Invoice invoice = invoiceRepository.findByIdWithDetails(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Invoice", id));
                return buildInvoiceResponse(invoice);
        }

        private void recordCashbook(Shift shift, InvoicePayment.PaymentMethod method, BigDecimal amount,
                        UUID warehouseId) {
                CashbookTransaction.FundType fundType = method == InvoicePayment.PaymentMethod.CASH
                                ? CashbookTransaction.FundType.CASH_111
                                : CashbookTransaction.FundType.BANK_112;
                cashbookRepository.save(CashbookTransaction.builder().warehouseId(warehouseId).shiftId(shift.getId())
                                .fundType(fundType).transactionType(CashbookTransaction.TransactionType.IN)
                                .referenceType("INVOICE")
                                .amount(amount).description("Thu tiền bán hàng - " + method.name())
                                .createdBy(shift.getCashierId().toString()).build());
        }

        private String generateInvoiceCode() {
                return "INV-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        }

        @Transactional(readOnly = true)
        public sme.backend.dto.response.PageResponse<InvoiceResponse> searchInvoices(
                        UUID shiftId, UUID warehouseId, String type, String keyword, Instant from, Instant to,
                        String paymentMethod, UUID cashierId,
                        org.springframework.data.domain.Pageable pageable) {

                org.springframework.data.domain.Page<Invoice> paged = invoiceRepository.searchInvoices(shiftId,
                                warehouseId, type, keyword, from, to, paymentMethod, cashierId, pageable);

                java.util.List<UUID> customerIds = paged.getContent().stream().map(Invoice::getCustomerId).filter(java.util.Objects::nonNull).distinct().toList();
                java.util.List<UUID> warehouseIds = paged.getContent().stream().map(Invoice::getWarehouseId).filter(java.util.Objects::nonNull).distinct().toList();
                java.util.List<UUID> cashierIds = paged.getContent().stream().map(Invoice::getCashierId).filter(java.util.Objects::nonNull).distinct().toList();
                java.util.Map<UUID, Customer> customerMap = customerIds.isEmpty() ? java.util.Collections.emptyMap() : customerRepository.findAllById(customerIds).stream().collect(java.util.stream.Collectors.toMap(Customer::getId, c -> c));
                java.util.Map<UUID, Warehouse> warehouseMap = warehouseIds.isEmpty() ? java.util.Collections.emptyMap() : warehouseRepository.findAllById(warehouseIds).stream().collect(java.util.stream.Collectors.toMap(Warehouse::getId, w -> w));
                java.util.Map<UUID, User> userMap = cashierIds.isEmpty() ? java.util.Collections.emptyMap() : userRepository.findAllById(cashierIds).stream().collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

                org.springframework.data.domain.Page<InvoiceResponse> mapped = paged.map(inv -> {
                        String customerName = "Khách lẻ";
                        String customerPhone = "—";
                        if (inv.getCustomerId() != null) {
                                Customer c = customerMap.get(inv.getCustomerId());
                                if (c != null) {
                                        customerName = c.getFullName();
                                        customerPhone = c.getPhoneNumber();
                                }
                        }
                        String warehouseName = null;
                        if (inv.getWarehouseId() != null) {
                                Warehouse w = warehouseMap.get(inv.getWarehouseId());
                                if (w != null) warehouseName = w.getName();
                                else warehouseName = "Kho không xác định";
                        }

                        // Lọc theo paymentMethod nếu có
                        List<InvoiceResponse.PaymentResponse> payments = inv.getPayments() != null
                                        ? inv.getPayments().stream()
                                                        .map(p -> InvoiceResponse.PaymentResponse.builder()
                                                                        .method(translatePaymentMethod(
                                                                                        p.getMethod().name()))
                                                                        .amount(p.getAmount())
                                                                        .reference(p.getReference())
                                                                        .build())
                                                        .toList()
                                        : List.of();

                        String cashierName = "Hệ thống";
                        if (inv.getCashierId() != null) {
                                User u = userMap.get(inv.getCashierId());
                                if (u != null) cashierName = u.getFullName();
                                else cashierName = "Không rõ";
                        }

                        List<InvoiceResponse.ItemResponse> itemResponses = List.of();

                        return InvoiceResponse.builder()
                                        .id(inv.getId())
                                        .code(inv.getCode())
                                        .type(inv.getType().name())
                                        .totalAmount(inv.getTotalAmount())
                                        .discountAmount(inv.getDiscountAmount())
                                        .finalAmount(inv.getFinalAmount())
                                        .customerId(inv.getCustomerId())
                                        .customerName(customerName)
                                        .customerPhone(customerPhone)
                                        .cashierName(cashierName)
                                        .warehouseId(inv.getWarehouseId())
                                        .warehouseName(warehouseName)
                                        .createdAt(inv.getCreatedAt())
                                        .items(itemResponses)
                                        .payments(payments)
                                        .build();
                });

                return sme.backend.dto.response.PageResponse.of(mapped);
        }

        private String generateReturnCode() {
                return "RET-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        }

        // ─────────────────────────────────────────────────────────
        // VOID INVOICE (Hủy hóa đơn) — chỉ MANAGER/ADMIN
        // ─────────────────────────────────────────────────────────
        @Transactional
        public InvoiceResponse voidInvoice(UUID invoiceId, UUID shiftId, String reason, UUID managerId) {
                // 1. Load invoice
                Invoice invoice = invoiceRepository.findByIdWithDetails(invoiceId)
                                .orElseThrow(() -> new ResourceNotFoundException("Invoice", invoiceId));

                // 2. Kiểm tra chỉ void được hóa đơn SALE
                if (invoice.getType() != Invoice.InvoiceType.SALE) {
                        throw new BusinessException("INVALID_VOID",
                                        "Chỉ có thể hủy hóa đơn bán hàng (SALE). Hóa đơn này là: " + invoice.getType());
                }

                // 3. Kiểm tra shift của hóa đơn này phải đang OPEN
                ShiftResponse shiftResp = shiftService.getById(invoice.getShiftId());
                if (!"OPEN".equals(shiftResp.getStatus())) {
                        throw new BusinessException("SHIFT_CLOSED",
                                        "Chỉ có thể hủy hóa đơn của ca làm việc đang mở. Ca này đã đóng.");
                }

                // 4. Hoàn kho cho từng sản phẩm
                for (InvoiceItem item : invoice.getItems()) {
                        if (item.getQuantity() > 0 && invoice.getWarehouseId() != null) {
                                inventoryService.returnToStock(
                                                item.getProductId(),
                                                invoice.getWarehouseId(),
                                                item.getQuantity(),
                                                invoiceId,
                                                "VOID_INVOICE",
                                                managerId.toString());
                                                
                                // NV-2: Dùng native query decrement an toàn
                                productRepository.decrementSoldQuantity(item.getProductId(), item.getQuantity());
                        }
                }

                // 5. Hoàn điểm khách hàng (nếu có)
                if (invoice.getCustomerId() != null) {
                        Customer customer = customerRepository.findById(invoice.getCustomerId()).orElse(null);
                        if (customer != null) {
                                // Hoàn lại điểm đã dùng
                                if (invoice.getPointsUsed() != null && invoice.getPointsUsed() > 0) {
                                        customer.addPoints(invoice.getPointsUsed());
                                }
                                // Trừ điểm đã tích
                                if (invoice.getPointsEarned() != null && invoice.getPointsEarned() > 0) {
                                        try {
                                                customer.deductPoints(invoice.getPointsEarned());
                                        } catch (Exception e) {
                                                log.warn("Không thể trừ điểm tích lũy khi hủy hóa đơn {}: {}",
                                                                invoice.getCode(), e.getMessage());
                                        }
                                }
                                customerRepository.save(customer);
                        }
                }

                // 6. Ghi cashbook âm (hoàn tiền)
                for (InvoicePayment payment : invoice.getPayments()) {
                        CashbookTransaction.FundType fund = payment.getMethod() == InvoicePayment.PaymentMethod.CASH
                                        ? CashbookTransaction.FundType.CASH_111
                                        : CashbookTransaction.FundType.BANK_112;
                        cashbookRepository.save(CashbookTransaction.builder()
                                        .warehouseId(invoice.getWarehouseId())
                                        .shiftId(invoice.getShiftId())
                                        .fundType(fund)
                                        .transactionType(CashbookTransaction.TransactionType.OUT)
                                        .referenceType("VOID_INVOICE")
                                        .referenceId(invoice.getId())
                                        .amount(payment.getAmount())
                                        .description("Hủy HĐ #" + invoice.getCode() + " — " + reason)
                                        .createdBy(managerId.toString())
                                        .build());
                }

                // 7. Đánh dấu invoice VOIDED
                invoice.setType(Invoice.InvoiceType.VOIDED);
                invoice.setVoidedBy(managerId);
                invoice.setVoidedAt(Instant.now());
                invoice.setVoidReason(reason);
                invoiceRepository.save(invoice);

                log.info("Invoice VOIDED: {} by manager {} — reason: {}", invoice.getCode(), managerId, reason);
                return buildInvoiceResponse(invoice);
        }

        private InvoiceResponse buildInvoiceResponse(Invoice invoice) {
                String cashierName = "Hệ thống";
                if (invoice.getCashierId() != null) {
                        cashierName = userRepository.findById(invoice.getCashierId())
                                        .map(User::getFullName)
                                        .orElse("Không xác định");
                }

                String voidedByName = null;
                if (invoice.getVoidedBy() != null) {
                        voidedByName = userRepository.findById(invoice.getVoidedBy())
                                        .map(User::getFullName)
                                        .orElse("Không xác định");
                }

                List<InvoiceResponse.ItemResponse> items = invoice.getItems().stream().map(it -> {
                        Product p = productRepository.findById(it.getProductId()).orElse(null);
                        return InvoiceResponse.ItemResponse.builder()
                                        .productId(it.getProductId())
                                        .productName(p != null ? p.getName() : "Sản phẩm không xác định")
                                        .isbnBarcode(p != null ? p.getIsbnBarcode() : "")
                                        .quantity(it.getQuantity())
                                        .returnedQuantity(it.getReturnedQuantity())
                                        .unitPrice(it.getUnitPrice())
                                        .macPrice(it.getMacPrice())
                                        .subtotal(it.getSubtotal())
                                        .build();
                }).toList();

                List<InvoiceResponse.PaymentResponse> payments = invoice.getPayments().stream()
                                .map(p -> InvoiceResponse.PaymentResponse.builder()
                                                .method(translatePaymentMethod(p.getMethod().name()))
                                                .amount(p.getAmount())
                                                .reference(p.getReference())
                                                .build())
                                .toList();

                String customerName = "Khách lẻ";
                String customerPhone = "";
                if (invoice.getCustomerId() != null) {
                        Customer c = customerRepository.findById(invoice.getCustomerId()).orElse(null);
                        if (c != null) {
                                customerName = c.getFullName();
                                customerPhone = c.getPhoneNumber();
                        }
                }

                return InvoiceResponse.builder()
                                .id(invoice.getId())
                                .code(invoice.getCode())
                                .shiftId(invoice.getShiftId())
                                .customerId(invoice.getCustomerId())
                                .customerName(customerName)
                                .customerPhone(customerPhone)
                                .type(invoice.getType().name())
                                .totalAmount(invoice.getTotalAmount())
                                .discountAmount(invoice.getDiscountAmount())
                                .finalAmount(invoice.getFinalAmount())
                                .pointsUsed(invoice.getPointsUsed())
                                .pointsEarned(invoice.getPointsEarned())
                                .cashierName(cashierName)
                                .warehouseId(invoice.getWarehouseId())
                                .warehouseName(invoice.getWarehouseId() != null
                                                ? warehouseRepository.findById(invoice.getWarehouseId())
                                                                .map(Warehouse::getName).orElse("Kho không xác định")
                                                : "Chưa gán kho")
                                .note(invoice.getNote())
                                .voidedBy(invoice.getVoidedBy())
                                .voidedByName(voidedByName)
                                .voidedAt(invoice.getVoidedAt())
                                .voidReason(invoice.getVoidReason())
                                .createdAt(invoice.getCreatedAt())
                                .items(items)
                                .payments(payments)
                                .build();
        }

        private String translatePaymentMethod(String method) {
                if (method == null)
                        return "Chưa xác định";
                return switch (method.toUpperCase()) {
                        case "CASH" -> "Tiền mặt";
                        case "BANK_TRANSFER" -> "Chuyển khoản";
                        case "CARD" -> "Thẻ (POS)";
                        case "E_WALLET" -> "Ví điện tử";
                        default -> method;
                };
        }

        public record RefundItem(UUID productId, int quantity) {
        }
}
