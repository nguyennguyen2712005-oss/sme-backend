package sme.backend.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.PaymentRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PaymentLinkResponse;
import sme.backend.entity.Order;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.OrderRepository;
import sme.backend.service.PayosService;
import sme.backend.service.OrderService;
import sme.backend.service.POSService;
import sme.backend.dto.request.PosCheckoutDraft;
import sme.backend.dto.request.CheckoutRequest;
import sme.backend.dto.response.InvoiceResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/payments/payos")
@RequiredArgsConstructor
@Slf4j
public class PayosController {

    private final PayosService payosService;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final POSService posService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${payos.return-url:https://sme-frontend-nine.vercel.app/payment/return}")
    private String defaultReturnUrl;

    @PostMapping("/create/{orderId}")
    public ResponseEntity<ApiResponse<PaymentLinkResponse>> createPaymentLink(
            @PathVariable UUID orderId,
            @RequestBody(required = false) PaymentRequest req) {
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        String returnUrl = (req != null && req.getReturnUrl() != null) ? req.getReturnUrl() : defaultReturnUrl;
        String cancelUrl = (req != null && req.getCancelUrl() != null) ? req.getCancelUrl() : defaultReturnUrl;

        String description = "DH " + order.getCode().replace("ORD-", "");
        Map<String, Object> payosRes = payosService.createPaymentLink(order.getCode(), order.getFinalAmount().longValue(), description, returnUrl, cancelUrl);

        if (payosRes.containsKey("data") && payosRes.get("data") != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payosRes.get("data");
            String checkoutUrl = (String) data.get("checkoutUrl");
            
            return ResponseEntity.ok(ApiResponse.ok(
                PaymentLinkResponse.builder()
                    .checkoutUrl(checkoutUrl)
                    .orderCode(order.getCode())
                    .gateway("PAYOS")
                    .build()
            ));
        }

        return ResponseEntity.badRequest().body(ApiResponse.<PaymentLinkResponse>builder().success(false).message("Lỗi tạo link thanh toán PayOS: " + payosRes.get("desc")).build());
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(@RequestBody Map<String, Object> webhookData) {
        log.info("Nhận Webhook từ PayOS: {}", webhookData);

        if (!payosService.verifyWebhook(webhookData)) {
            log.error("Xác thực Webhook PayOS thất bại!");
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) webhookData.get("data");
        if (data == null) return ResponseEntity.ok("OK");

        String orderCodeNum = String.valueOf(data.get("orderCode"));
        // Extract the original 13-digit timestamp if a retry suffix was appended
        String baseOrderCodeNum = orderCodeNum;
        if (orderCodeNum.length() > 13) {
            baseOrderCodeNum = orderCodeNum.substring(0, 13);
        }
        
        String originalOrderCode = "ORD-" + baseOrderCodeNum;
        String code = (String) webhookData.get("code"); // '00' is success

        if (!"00".equals(code)) {
            return ResponseEntity.ok("OK"); // Ignore failed webhooks
        }

        Order order = orderRepository.findByCode(originalOrderCode).orElse(null);
        if (order != null) {
            if (order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
                orderService.markAsPaid(order.getId(), "PayOS");
            }

            // Chuyển trạng thái đơn hàng (nếu đang chờ thanh toán hoặc chờ xác nhận)
            if (order.getStatus() == Order.OrderStatus.PAYMENT_PENDING) {
                orderService.updateStatus(order.getId(), Order.OrderStatus.PENDING.name(), "Đã thanh toán qua PayOS", null, "PayOS", "SYSTEM");
            }
        } else {
            // Check if it is a POS draft order
            PosCheckoutDraft draft = (PosCheckoutDraft) redisTemplate.opsForValue().get("pos_checkout:" + baseOrderCodeNum);
            if (draft != null) {
                try {
                    CheckoutRequest req = draft.getCheckoutRequest();
                    req.setPayments(new ArrayList<>());

                    CheckoutRequest.PaymentRequest payment = new CheckoutRequest.PaymentRequest();
                    payment.setMethod("BANK_TRANSFER");
                    payment.setAmount(BigDecimal.valueOf(draft.getAmount()));
                    payment.setReference(String.valueOf(data.get("reference")));
                    req.getPayments().add(payment);

                    InvoiceResponse invoice = posService.checkout(req, draft.getCashierId(), draft.getWarehouseId());
                    
                    // Notify frontend
                    messagingTemplate.convertAndSend("/topic/pos-payment/" + baseOrderCodeNum, ApiResponse.ok("Thanh toán PayOS thành công", invoice));
                    
                    // Cleanup Redis
                    redisTemplate.delete("pos_checkout:" + baseOrderCodeNum);
                } catch (Exception e) {
                    log.error("Lỗi khi checkout POS draft từ webhook PayOS: ", e);
                    messagingTemplate.convertAndSend("/topic/pos-payment/" + baseOrderCodeNum, ApiResponse.<String>builder().success(false).message("Lỗi tạo hóa đơn: " + e.getMessage()).build());
                }
            }
        }

        return ResponseEntity.ok("OK");
    }

    /**
     * Cashier xác nhận thủ công rằng khách đã thanh toán qua PayOS.
     * Dùng khi webhook không tới được (local dev) hoặc polling không hoạt động.
     */
    @PostMapping("/pos-confirm/{orderCode}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER')")
    public ResponseEntity<ApiResponse<InvoiceResponse>> posManualConfirm(
            @PathVariable String orderCode) {
        String baseCode = orderCode.length() > 13 ? orderCode.substring(0, 13) : orderCode;

        PosCheckoutDraft draft = (PosCheckoutDraft) redisTemplate.opsForValue().get("pos_checkout:" + baseCode);
        if (draft == null) {
            throw new sme.backend.exception.BusinessException("DRAFT_NOT_FOUND",
                    "Đơn hàng không tồn tại hoặc đã được xử lý trước đó");
        }

        CheckoutRequest req = draft.getCheckoutRequest();
        req.setPayments(new ArrayList<>());

        CheckoutRequest.PaymentRequest payment = new CheckoutRequest.PaymentRequest();
        payment.setMethod("BANK_TRANSFER");
        payment.setAmount(BigDecimal.valueOf(draft.getAmount()));
        payment.setReference("PayOS - Xác nhận thủ công");
        req.getPayments().add(payment);

        InvoiceResponse invoice = posService.checkout(req, draft.getCashierId(), draft.getWarehouseId());
        messagingTemplate.convertAndSend(
                "/topic/pos-payment/" + baseCode,
                ApiResponse.ok("Thanh toán xác nhận thành công", invoice));
        redisTemplate.delete("pos_checkout:" + baseCode);

        return ResponseEntity.ok(ApiResponse.ok("Thanh toán xác nhận thành công", invoice));
    }

    @GetMapping("/verify/{orderCode}")
    public ResponseEntity<ApiResponse<Boolean>> verifyPayment(@PathVariable String orderCode) {
        try {
            Long orderCodeNum = Long.parseLong(orderCode);
            Map<String, Object> statusObj = payosService.getPaymentStatus(orderCodeNum);
            
            if (statusObj != null && "00".equals(statusObj.get("code"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) statusObj.get("data");
                if (data != null && "PAID".equals(data.get("status"))) {
                    // Extract original order code
                    String baseOrderCodeNum = orderCode;
                    if (orderCode.length() > 13) {
                        baseOrderCodeNum = orderCode.substring(0, 13);
                    }
                    String originalOrderCode = "ORD-" + baseOrderCodeNum;

                    Order order = orderRepository.findByCode(originalOrderCode).orElse(null);
                    if (order != null) {
                        if (order.getPaymentStatus() == Order.PaymentStatus.UNPAID) {
                            orderService.markAsPaid(order.getId(), "PayOS");
                        }
                        if (order.getStatus() == Order.OrderStatus.PAYMENT_PENDING) {
                            orderService.updateStatus(order.getId(), Order.OrderStatus.PENDING.name(), "Đã thanh toán qua PayOS", null, "PayOS", "SYSTEM");
                        }
                    } else if (order == null) {
                        // POS draft check
                        PosCheckoutDraft draft = (PosCheckoutDraft) redisTemplate.opsForValue().get("pos_checkout:" + baseOrderCodeNum);
                        if (draft != null) {
                            CheckoutRequest req = draft.getCheckoutRequest();
                            req.setPayments(new ArrayList<>());
                            CheckoutRequest.PaymentRequest payment = new CheckoutRequest.PaymentRequest();
                            payment.setMethod("BANK_TRANSFER");
                            payment.setAmount(BigDecimal.valueOf(draft.getAmount()));
                            payment.setReference("Verified by frontend");
                            req.getPayments().add(payment);

                            InvoiceResponse invoice = posService.checkout(req, draft.getCashierId(), draft.getWarehouseId());
                            messagingTemplate.convertAndSend("/topic/pos-payment/" + baseOrderCodeNum, ApiResponse.ok("Thanh toán PayOS thành công", invoice));
                            redisTemplate.delete("pos_checkout:" + baseOrderCodeNum);
                        }
                    }
                    return ResponseEntity.ok(ApiResponse.ok(true));
                }
            }
            return ResponseEntity.ok(ApiResponse.ok(false));
        } catch (Exception e) {
            log.error("Lỗi khi xác minh đơn hàng PayOS qua API: ", e);
            return ResponseEntity.ok(ApiResponse.ok(false));
        }
    }
}
