package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CheckoutRequest;
import sme.backend.dto.request.CloseShiftRequest;
import sme.backend.dto.request.OpenShiftRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.InvoiceResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.dto.response.ShiftResponse;
import sme.backend.entity.Warehouse;
import sme.backend.exception.BusinessException;
import sme.backend.repository.InvoiceRepository;
import sme.backend.repository.WarehouseRepository;
import sme.backend.security.UserPrincipal;
import sme.backend.service.POSService;
import sme.backend.service.ShiftService;
import sme.backend.service.PdfService;
import sme.backend.service.PayosService;
import sme.backend.service.VnPayService;
import sme.backend.repository.CustomerRepository;
import sme.backend.config.AppProperties;
import sme.backend.dto.request.PosCheckoutDraft;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
public class POSController {

    private final ShiftService shiftService;
    private final POSService posService;
    private final InvoiceRepository invoiceRepository;
    private final WarehouseRepository warehouseRepository;
    private final PdfService pdfService;
    private final PayosService payosService;
    private final VnPayService vnPayService;
    private final AppProperties appProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/shifts/open")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> openShift(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody OpenShiftRequest req) {

        UUID warehouseId = principal.getWarehouseId();
        if (warehouseId == null) {
            warehouseId = getDefaultWarehouseId();
        }

        if (warehouseId == null) {
            throw new BusinessException("NO_WAREHOUSE",
                    "Vui lòng chọn chi nhánh làm việc trước khi thao tác POS. Sử dụng menu chọn chi nhánh ở thanh điều hướng.");
        }
        ShiftResponse shift = shiftService.openShift(
                principal.getId(), warehouseId, req);
        return ResponseEntity.ok(ApiResponse.ok("Mở ca thành công", shift));
    }

    @PostMapping("/shifts/close")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> closeShift(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CloseShiftRequest req) {
        ShiftResponse shift = shiftService.closeShift(principal, req);
        return ResponseEntity.ok(ApiResponse.ok("Đóng ca thành công", shift));
    }

    @GetMapping("/shifts/current")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<ShiftResponse>> getCurrentShift(
            @AuthenticationPrincipal UserPrincipal principal) {
        try {
            var shift = shiftService.getOpenShiftByCashier(principal.getId());
            return ResponseEntity.ok(ApiResponse.ok(shiftService.mapToResponse(shift)));
        } catch (BusinessException e) {
            // Thay vì ném 400 Bad Request, trả về 200 OK với data = null để Frontend biết
            // là chưa mở ca
            return ResponseEntity.ok(ApiResponse.ok(null));
        }
    }

    @GetMapping("/shifts/pending")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<ShiftResponse>>> getPendingShifts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId) {

        UUID targetWarehouseId;
        if (principal.getRole() == sme.backend.entity.User.UserRole.ROLE_ADMIN) {
            targetWarehouseId = warehouseId;
        } else {
            targetWarehouseId = principal.getWarehouseId();
        }

        return ResponseEntity.ok(ApiResponse.ok(shiftService.getPendingShifts(targetWarehouseId)));
    }

    @PostMapping("/shifts/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ShiftResponse>> approveShift(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        ShiftResponse shift = shiftService.approveShift(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Duyệt ca thành công", shift));
    }

    @GetMapping("/shifts")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<ShiftResponse>>> searchShifts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID targetWarehouseId = (principal.getRole() == sme.backend.entity.User.UserRole.ROLE_ADMIN)
                ? warehouseId
                : principal.getWarehouseId();

        var pageable = PageRequest.of(page, size, org.springframework.data.domain.Sort.by("openedAt").descending());
        return ResponseEntity
                .ok(ApiResponse.ok(PageResponse.of(shiftService.searchShifts(targetWarehouseId, pageable))));
    }

    @GetMapping("/shifts/{id}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ShiftResponse>> getShift(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(shiftService.getById(id)));
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> checkout(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CheckoutRequest req) {

        UUID warehouseId = principal.getWarehouseId();
        if (warehouseId == null) {
            warehouseId = getDefaultWarehouseId();
        }

        if (warehouseId == null) {
            throw new BusinessException("NO_WAREHOUSE",
                    "Vui lòng chọn chi nhánh làm việc trước khi thao tác POS. Sử dụng menu chọn chi nhánh ở thanh điều hướng.");
        }
        InvoiceResponse invoice = posService.checkout(
                req, principal.getId(), warehouseId);
        return ResponseEntity.ok(ApiResponse.ok("Thanh toán thành công", invoice));
    }

    @PostMapping("/qr-checkout-init")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> initQrCheckout(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "PAYOS") String gateway,
            @Valid @RequestBody CheckoutRequest req) {

        UUID warehouseId = principal.getWarehouseId();
        if (warehouseId == null) {
            warehouseId = getDefaultWarehouseId();
        }

        if (warehouseId == null) {
            throw new BusinessException("NO_WAREHOUSE",
                    "Vui lòng chọn chi nhánh làm việc trước khi thao tác POS.");
        }

        // Validate shift
        sme.backend.entity.Shift shift = shiftService.getOpenShiftByCashier(principal.getId());
        if (!shift.getId().equals(req.getShiftId())) {
            throw new BusinessException("SHIFT_MISMATCH",
                    "shiftId không khớp với ca làm việc đang mở");
        }

        // Calculate amount
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (CheckoutRequest.CartItemRequest item : req.getItems()) {
            totalAmount = totalAmount.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }

        BigDecimal discountAmount = BigDecimal.ZERO;
        if (req.getOrderDiscountAmt() != null) {
            discountAmount = discountAmount.add(req.getOrderDiscountAmt());
        }
        if (req.getCouponDiscountAmt() != null) {
            discountAmount = discountAmount.add(req.getCouponDiscountAmt());
        }

        int pointsToUse = req.getPointsToUse() != null ? req.getPointsToUse() : 0;
        if (pointsToUse > 0) {
            BigDecimal pointsDiscount = BigDecimal.valueOf(pointsToUse)
                    .multiply(BigDecimal.valueOf(appProperties.getBusiness().getLoyaltyPointsRedeemValue()));
            discountAmount = discountAmount.add(pointsDiscount);
        }

        if (discountAmount.compareTo(totalAmount) > 0) discountAmount = totalAmount;
        BigDecimal finalAmount = totalAmount.subtract(discountAmount);

        // Generate temporary POS order code
        String orderCodeStr = String.valueOf(System.currentTimeMillis());
        
        // Save to Redis (TTL 15 mins)
        PosCheckoutDraft draft = PosCheckoutDraft.builder()
                .checkoutRequest(req)
                .cashierId(principal.getId())
                .warehouseId(warehouseId)
                .amount(finalAmount.longValue())
                .build();
                
        redisTemplate.opsForValue().set("pos_checkout:" + orderCodeStr, draft, 15, java.util.concurrent.TimeUnit.MINUTES);

        String description = "POS " + orderCodeStr;
        String checkoutUrl = "";
        String qrCode = null;

        if ("VNPAY".equalsIgnoreCase(gateway)) {
            checkoutUrl = vnPayService.createPaymentUrl(orderCodeStr, finalAmount.longValue(), description, "https://sme-frontend-nine.vercel.app/pos/payment-return", "127.0.0.1");
        } else {
            Map<String, Object> payosRes = payosService.createPaymentLink(orderCodeStr, finalAmount.longValue(), description, "https://sme-frontend-nine.vercel.app/pos/payment-return", "https://sme-frontend-nine.vercel.app/pos/payment-return");
            if (payosRes.containsKey("data") && payosRes.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) payosRes.get("data");
                checkoutUrl = (String) data.get("checkoutUrl");
                if (data.containsKey("qrCode")) {
                    qrCode = (String) data.get("qrCode");
                }
            } else {
                throw new BusinessException("PAYMENT_ERROR", "Lỗi tạo link thanh toán PayOS: " + payosRes.get("desc"));
            }
        }

        Map<String, Object> responseData = new java.util.HashMap<>();
        responseData.put("checkoutUrl", checkoutUrl);
        responseData.put("orderCode", orderCodeStr);
        responseData.put("amount", finalAmount);
        responseData.put("gateway", gateway.toUpperCase());
        if (qrCode != null) {
            responseData.put("qrCode", qrCode);
        }

        return ResponseEntity.ok(ApiResponse.ok("Khởi tạo mã QR thành công", responseData));
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoice(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(posService.getInvoice(id)));
    }

    @GetMapping("/invoices/{id}/pdf")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<byte[]> getInvoicePdf(@PathVariable UUID id) {
        InvoiceResponse invoice = posService.getInvoice(id);
        byte[] pdfBytes = pdfService.generatePdfFromTemplate("invoice", Map.of("invoice", invoice));

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=invoice-" + invoice.getCode() + ".pdf")
                .body(pdfBytes);
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<InvoiceResponse>>> getInvoicesByShift(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) UUID shiftId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) UUID cashierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID warehouseId) {

        UUID targetWarehouseId = principal.getWarehouseId();
        if (principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            targetWarehouseId = warehouseId;
        }

        String kw = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        String pm = (paymentMethod != null && !paymentMethod.trim().isEmpty()) ? paymentMethod.trim() : null;
        String t = (type != null && !type.trim().isEmpty()) ? type.trim() : null;
        return ResponseEntity.ok(ApiResponse
                .ok(posService.searchInvoices(shiftId, targetWarehouseId, t, kw, from, to, pm, cashierId, PageRequest.of(page, size))));
    }

    @GetMapping("/invoices/code/{code}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> getInvoiceByCode(@PathVariable String code) {
        var invoice = invoiceRepository.findByCode(code)
                .orElseThrow(() -> new sme.backend.exception.ResourceNotFoundException(
                        "Không tìm thấy hóa đơn mã: " + code));

        return ResponseEntity.ok(ApiResponse.ok(posService.getInvoice(invoice.getId())));
    }

    // ─── VOID INVOICE (Hủy hóa đơn) ───────────────────────
    public static class VoidInvoiceRequest {
        private UUID shiftId;
        private String reason;

        public UUID getShiftId() {
            return shiftId;
        }

        public void setShiftId(UUID shiftId) {
            this.shiftId = shiftId;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    @PostMapping("/invoices/{id}/void")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<?> voidInvoice(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody VoidInvoiceRequest req) {
        try {
            InvoiceResponse invoice = posService.voidInvoice(
                    id, req.getShiftId(), req.getReason(), principal.getId());
            return ResponseEntity.ok(ApiResponse.ok("Hủy hóa đơn thành công", invoice));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(java.util.Map.of("message", "Lỗi nội bộ: " + e.getMessage(), "cause",
                    e.getCause() != null ? e.getCause().toString() : "null"));
        }
    }

    // Đã chuyển từ record sang static class chuẩn để tránh lỗi ClassLoader của
    // Maven
    public static class RefundRequestDTO {
        private UUID originalInvoiceId;
        private UUID shiftId;
        private List<POSService.RefundItem> items;
        private String returnDestination;
        private String note;

        public UUID getOriginalInvoiceId() {
            return originalInvoiceId;
        }

        public void setOriginalInvoiceId(UUID originalInvoiceId) {
            this.originalInvoiceId = originalInvoiceId;
        }

        public UUID getShiftId() {
            return shiftId;
        }

        public void setShiftId(UUID shiftId) {
            this.shiftId = shiftId;
        }

        public List<POSService.RefundItem> getItems() {
            return items;
        }

        public void setItems(List<POSService.RefundItem> items) {
            this.items = items;
        }

        public String getReturnDestination() {
            return returnDestination;
        }

        public void setReturnDestination(String returnDestination) {
            this.returnDestination = returnDestination;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    @PostMapping("/refund")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> refund(
            @AuthenticationPrincipal sme.backend.security.UserPrincipal principal, // Dùng đường dẫn tuyệt đối an toàn
            @RequestBody RefundRequestDTO req) {

        if (principal.getWarehouseId() == null) {
            throw new BusinessException("NO_WAREHOUSE",
                    "Vui lòng chọn chi nhánh làm việc trước khi thao tác POS. Sử dụng menu chọn chi nhánh ở thanh điều hướng.");
        }

        InvoiceResponse invoice = posService.refund(
                req.getOriginalInvoiceId(), req.getShiftId(), req.getItems(),
                req.getReturnDestination(), principal.getId(), principal.getWarehouseId(), req.getNote());

        return ResponseEntity.ok(ApiResponse.ok("Trả hàng thành công", invoice));
    }

    private UUID getDefaultWarehouseId() {
        return warehouseRepository.findByCode("KHO-01")
                .or(() -> warehouseRepository.findByCode("STORE"))
                .or(() -> warehouseRepository.findAll().stream()
                        .filter(w -> w.getName().contains("Cửa hàng") && w.getIsActive())
                        .findFirst())
                .or(() -> warehouseRepository.findAll().stream()
                        .filter(Warehouse::getIsActive)
                        .findFirst())
                .map(Warehouse::getId)
                .orElse(null);
    }
}