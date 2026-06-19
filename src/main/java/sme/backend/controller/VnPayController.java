package sme.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.PaymentRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PaymentLinkResponse;
import sme.backend.entity.Order;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.OrderRepository;
import sme.backend.service.VnPayService;
import sme.backend.service.OrderService;
import sme.backend.service.POSService;
import sme.backend.dto.request.PosCheckoutDraft;
import sme.backend.dto.request.CheckoutRequest;
import sme.backend.dto.response.InvoiceResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/payments/vnpay")
@RequiredArgsConstructor
@Slf4j
public class VnPayController {

    private final VnPayService vnPayService;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final POSService posService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${vnpay.return-url:https://sme-frontend-nine.vercel.app/payment/return}")
    private String defaultReturnUrl;

    @PostMapping("/create/{orderId}")
    public ResponseEntity<ApiResponse<PaymentLinkResponse>> createPaymentUrl(
            @PathVariable UUID orderId,
            @RequestBody(required = false) PaymentRequest req,
            HttpServletRequest request) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        String returnUrl = (req != null && req.getReturnUrl() != null) ? req.getReturnUrl() : defaultReturnUrl;
        
        String ipAddress = request.getHeader("X-FORWARDED-FOR");
        if (ipAddress == null || "".equals(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }

        String description = "Thanh toan don hang " + order.getCode();
        String paymentUrl = vnPayService.createPaymentUrl(order.getCode(), order.getFinalAmount().longValue(), description, returnUrl, ipAddress);

        return ResponseEntity.ok(ApiResponse.ok(
            PaymentLinkResponse.builder()
                .checkoutUrl(paymentUrl)
                .orderCode(order.getCode())
                .gateway("VNPAY")
                .build()
        ));
    }

    @GetMapping("/ipn")
    public ResponseEntity<Map<String, String>> handleIpn(HttpServletRequest request) {
        log.info("Nhận IPN từ VNPay...");
        Map<String, String> fields = new HashMap<>();
        for (Enumeration<String> params = request.getParameterNames(); params.hasMoreElements();) {
            String fieldName = params.nextElement();
            String fieldValue = request.getParameter(fieldName);
            if ((fieldValue != null) && (fieldValue.length() > 0)) {
                fields.put(fieldName, fieldValue);
            }
        }

        Map<String, String> response = new HashMap<>();
        
        if (!vnPayService.verifyIpn(fields)) {
            log.error("Xác thực IPN VNPay thất bại!");
            response.put("RspCode", "97");
            response.put("Message", "Invalid Checksum");
            return ResponseEntity.ok(response);
        }

        String orderCode = fields.get("vnp_TxnRef");
        String vnp_ResponseCode = fields.get("vnp_ResponseCode");

        Order order = orderRepository.findByCode(orderCode).orElse(null);
        if (order == null) {
            // Check if it's a POS draft
            PosCheckoutDraft draft = (PosCheckoutDraft) redisTemplate.opsForValue().get("pos_checkout:" + orderCode);
            if (draft != null) {
                if ("00".equals(vnp_ResponseCode)) {
                    try {
                        CheckoutRequest req = draft.getCheckoutRequest();
                        if (req.getPayments() == null) req.setPayments(new ArrayList<>());
                        
                        CheckoutRequest.PaymentRequest payment = new CheckoutRequest.PaymentRequest();
                        payment.setMethod("BANK_TRANSFER");
                        payment.setAmount(BigDecimal.valueOf(draft.getAmount()));
                        payment.setReference(fields.get("vnp_TransactionNo"));
                        req.getPayments().add(payment);

                        InvoiceResponse invoice = posService.checkout(req, draft.getCashierId(), draft.getWarehouseId());
                        
                        // Notify frontend
                        messagingTemplate.convertAndSend("/topic/pos-payment/" + orderCode, ApiResponse.ok("Thanh toán VNPay thành công", invoice));
                        
                        // Cleanup Redis
                        redisTemplate.delete("pos_checkout:" + orderCode);
                    } catch (Exception e) {
                        log.error("Lỗi khi checkout POS draft từ IPN VNPay: ", e);
                        messagingTemplate.convertAndSend("/topic/pos-payment/" + orderCode, ApiResponse.<String>builder().success(false).message("Lỗi tạo hóa đơn: " + e.getMessage()).build());
                    }
                }
                
                response.put("RspCode", "00");
                response.put("Message", "Confirm Success");
                return ResponseEntity.ok(response);
            }

            response.put("RspCode", "01");
            response.put("Message", "Order not found");
            return ResponseEntity.ok(response);
        }

        if (order.getPaymentStatus() == Order.PaymentStatus.PAID) {
            response.put("RspCode", "02");
            response.put("Message", "Order already confirmed");
            return ResponseEntity.ok(response);
        }

        if ("00".equals(vnp_ResponseCode)) {
            orderService.markAsPaid(order.getId(), "VNPay");
            orderService.updateStatus(order.getId(), Order.OrderStatus.PENDING.name(), "Đã thanh toán qua VNPay", null, "VNPay", "SYSTEM");
            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
        } else {
            response.put("RspCode", "00"); // Let VNPay know we received it, but it's failed
            response.put("Message", "Transaction failed");
        }

        return ResponseEntity.ok(response);
    }
}
