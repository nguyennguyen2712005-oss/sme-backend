package sme.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender javaMailSender;

    @Value("${spring.mail.username:}")
    private String senderEmail;

    /**
     * Gửi email thông báo đơn hàng
     */
    @Async
    public void sendOrderStatusEmail(String toEmail, String customerName, String orderCode, String status,
            double finalAmount) {
        if (senderEmail == null || senderEmail.isBlank() || toEmail == null || toEmail.isBlank()) {
            log.warn("Không thể gửi email vì thiếu địa chỉ người gửi hoặc người nhận.");
            return;
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(senderEmail);
            helper.setTo(toEmail);

            String subject = "";
            String htmlContent = "";

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

            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            javaMailSender.send(message);
            log.info("Đã gửi email thông báo trạng thái đơn hàng {} đến {}", orderCode, toEmail);

        } catch (MessagingException e) {
            log.error("Lỗi khi gửi email đến {}: {} ({})", toEmail, e.getMessage(), e.getClass().getSimpleName(), e);
        } catch (Exception e) {
            log.error("Lỗi không mong đợi khi gửi email đến {}: {} ({})", toEmail, e.getMessage(), e.getClass().getSimpleName(), e);
        }
    }

    @Async
    public void sendForgotPasswordEmail(String toEmail, String otp) {
        log.info("Bắt đầu tiến trình gửi OTP đến email: {}", toEmail);
        
        if (senderEmail == null || senderEmail.isBlank() || toEmail == null || toEmail.isBlank()) {
            log.warn("Không thể gửi email vì thiếu địa chỉ người gửi hoặc người nhận. senderEmail='{}', toEmail='{}'", senderEmail, toEmail);
            return;
        }

        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(senderEmail);
            helper.setTo(toEmail);
            helper.setSubject("Mã OTP khôi phục mật khẩu - SME Bookstore");

            String htmlContent = String.format(
                    "<h3>Xin chào,</h3>" +
                    "<p>Bạn đã yêu cầu khôi phục mật khẩu. Dưới đây là mã OTP của bạn (có hiệu lực trong 10 phút):</p>" +
                    "<h2 style='color: #2e6c80;'>%s</h2>" +
                    "<p>Nếu bạn không yêu cầu, vui lòng bỏ qua email này.</p>" +
                    "<br/><p>Trân trọng,<br/>Đội ngũ SME Bookstore</p>",
                    otp);

            helper.setText(htmlContent, true);

            javaMailSender.send(message);
            log.info("Đã gửi email OTP khôi phục mật khẩu đến {}", toEmail);

        } catch (MessagingException e) {
            log.error("Lỗi khi gửi email OTP đến {}: {} ({})", toEmail, e.getMessage(), e.getClass().getSimpleName(), e);
        } catch (Exception e) {
            log.error("Lỗi không mong đợi khi gửi email OTP đến {}: {} ({})", toEmail, e.getMessage(), e.getClass().getSimpleName(), e);
        }
    }
}
