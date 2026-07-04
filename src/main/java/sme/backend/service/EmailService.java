package sme.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Gửi email qua Brevo HTTP API (https://api.brevo.com/v3/smtp/email).
 * Không dùng SMTP vì Railway (gói Free/Hobby) chặn outbound port 465/587/25.
 * Brevo cho phép xác minh 1 email gửi đơn lẻ (Single Sender), không cần sở hữu domain riêng.
 */
@Service
@Slf4j
public class EmailService {

    private static final String BREVO_API_URL = "https://api.brevo.com/v3/smtp/email";

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${brevo.api-key:}")
    private String brevoApiKey;

    @Value("${brevo.from-email:}")
    private String fromEmail;

    @Value("${brevo.from-name:SME Bookstore}")
    private String fromName;

    /**
     * Gửi email thông báo đơn hàng
     */
    @Async
    public void sendOrderStatusEmail(String toEmail, String customerName, String orderCode, String status,
            double finalAmount) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Không thể gửi email vì thiếu địa chỉ người nhận.");
            return;
        }

        String subject;
        String htmlContent;

        if ("PENDING".equals(status) || "WAITING_FOR_CONSOLIDATION".equals(status)) {
            subject = "Xác nhận đơn hàng #" + orderCode;
            htmlContent = String.format(
                    "<h3>Xin chào %s,</h3>" +
                            "<p>Cảm ơn bạn đã mua sắm tại SME Bookstore.</p>" +
                            "<p>Đơn hàng <strong>#%s</strong> của bạn (tổng giá trị: %,.0f VNĐ) đã được xác nhận và đang được chuẩn bị.</p>"
                            +
                            "<br/><p>Trân trọng,<br/>Đội ngũ SME Bookstore</p>",
                    customerName, orderCode, finalAmount);
        } else if ("DELIVERED".equals(status)) {
            subject = "Đơn hàng #" + orderCode + " giao thành công";
            htmlContent = String.format(
                    "<h3>Xin chào %s,</h3>" +
                            "<p>Đơn hàng <strong>#%s</strong> của bạn đã được giao thành công.</p>" +
                            "<p>Cảm ơn bạn đã tin tưởng và sử dụng dịch vụ của chúng tôi!</p>" +
                            "<br/><p>Trân trọng,<br/>Đội ngũ SME Bookstore</p>",
                    customerName, orderCode);
        } else if ("CANCELLED".equals(status)) {
            subject = "Đơn hàng #" + orderCode + " đã bị hủy";
            htmlContent = String.format(
                    "<h3>Xin chào %s,</h3>" +
                            "<p>Chúng tôi rất tiếc phải thông báo đơn hàng <strong>#%s</strong> của bạn đã bị hủy.</p>"
                            +
                            "<p>Nếu bạn đã thanh toán, tiền sẽ được hoàn lại trong thời gian sớm nhất.</p>" +
                            "<br/><p>Trân trọng,<br/>Đội ngũ SME Bookstore</p>",
                    customerName, orderCode);
        } else {
            return; // Không gửi email cho các trạng thái khác
        }

        sendViaBrevo(toEmail, subject, htmlContent, "đơn hàng " + orderCode);
    }

    @Async
    public void sendForgotPasswordEmail(String toEmail, String otp) {
        log.info("Bắt đầu tiến trình gửi OTP đến email: {}", toEmail);

        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Không thể gửi email OTP vì thiếu địa chỉ người nhận.");
            return;
        }

        String htmlContent = String.format(
                "<h3>Xin chào,</h3>" +
                        "<p>Bạn đã yêu cầu khôi phục mật khẩu. Dưới đây là mã OTP của bạn (có hiệu lực trong 10 phút):</p>"
                        +
                        "<h2 style='color: #2e6c80;'>%s</h2>" +
                        "<p>Nếu bạn không yêu cầu, vui lòng bỏ qua email này.</p>" +
                        "<br/><p>Trân trọng,<br/>Đội ngũ SME Bookstore</p>",
                otp);

        sendViaBrevo(toEmail, "Mã OTP khôi phục mật khẩu - SME Bookstore", htmlContent, "OTP");
    }

    private void sendViaBrevo(String toEmail, String subject, String htmlContent, String logLabel) {
        if (brevoApiKey == null || brevoApiKey.isBlank() || fromEmail == null || fromEmail.isBlank()) {
            log.warn("Không thể gửi email vì thiếu BREVO_API_KEY hoặc BREVO_FROM_EMAIL.");
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("api-key", brevoApiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                    "sender", Map.of("email", fromEmail, "name", fromName),
                    "to", List.of(Map.of("email", toEmail)),
                    "subject", subject,
                    "htmlContent", htmlContent);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(BREVO_API_URL, request, String.class);

            log.info("Đã gửi email ({}) đến {}", logLabel, toEmail);
        } catch (HttpClientErrorException e) {
            log.error("Lỗi khi gửi email ({}) đến {} qua Brevo: {} - {}", logLabel, toEmail, e.getStatusCode(),
                    e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Lỗi không mong đợi khi gửi email ({}) đến {}: {}", logLabel, toEmail, e.getMessage(), e);
        }
    }
}
