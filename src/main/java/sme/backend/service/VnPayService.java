package sme.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sme.backend.entity.Order;
import sme.backend.utils.HmacUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class VnPayService {

    @Value("${vnpay.tmn-code}")
    private String vnp_TmnCode;

    @Value("${vnpay.hash-secret}")
    private String secretKey;

    @Value("${vnpay.pay-url}")
    private String vnp_PayUrl;

    public String createPaymentUrl(String orderCode, Long amountValue, String description, String returnUrl, String ipAddress) {
        String vnp_Version = "2.1.0";
        String vnp_Command = "pay";
        String vnp_TxnRef = orderCode;
        String vnp_OrderInfo = description;
        String vnp_OrderType = "other";
        String vnp_Amount = String.valueOf(amountValue * 100);
        String vnp_Locale = "vn";
        
        Calendar cld = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        String vnp_CreateDate = formatter.format(cld.getTime());

        cld.add(Calendar.MINUTE, 15);
        String vnp_ExpireDate = formatter.format(cld.getTime());

        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", vnp_Version);
        vnp_Params.put("vnp_Command", vnp_Command);
        vnp_Params.put("vnp_TmnCode", vnp_TmnCode);
        vnp_Params.put("vnp_Amount", vnp_Amount);
        vnp_Params.put("vnp_CurrCode", "VND");
        vnp_Params.put("vnp_TxnRef", vnp_TxnRef);
        vnp_Params.put("vnp_OrderInfo", vnp_OrderInfo);
        vnp_Params.put("vnp_OrderType", vnp_OrderType);
        vnp_Params.put("vnp_Locale", vnp_Locale);
        vnp_Params.put("vnp_ReturnUrl", returnUrl);
        vnp_Params.put("vnp_IpAddr", ipAddress);
        vnp_Params.put("vnp_CreateDate", vnp_CreateDate);
        vnp_Params.put("vnp_ExpireDate", vnp_ExpireDate);

        List<String> fieldNames = new ArrayList<>(vnp_Params.keySet());
        Collections.sort(fieldNames);
        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();

        try {
            for (String fieldName : fieldNames) {
                String fieldValue = vnp_Params.get(fieldName);
                if ((fieldValue != null) && (fieldValue.length() > 0)) {
                    hashData.append(fieldName);
                    hashData.append('=');
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                    hashData.append('&');
                    
                    query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString()));
                    query.append('=');
                    query.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                    query.append('&');
                }
            }
        } catch (Exception e) {
            log.error("Lỗi khi tạo tham số VNPay: ", e);
        }

        String queryUrl = query.substring(0, query.length() - 1);
        String vnp_SecureHash = HmacUtil.hmacSha512(secretKey, hashData.substring(0, hashData.length() - 1));
        queryUrl += "&vnp_SecureHash=" + vnp_SecureHash;
        
        return vnp_PayUrl + "?" + queryUrl;
    }

    public boolean verifyIpn(Map<String, String> fields) {
        try {
            Map<String, String> vnp_Params = new HashMap<>();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String fieldName = entry.getKey();
                String fieldValue = entry.getValue();
                if ((fieldValue != null) && (fieldValue.length() > 0)) {
                    vnp_Params.put(fieldName, fieldValue);
                }
            }
            
            String vnp_SecureHash = vnp_Params.remove("vnp_SecureHash");
            vnp_Params.remove("vnp_SecureHashType");

            List<String> fieldNames = new ArrayList<>(vnp_Params.keySet());
            Collections.sort(fieldNames);
            StringBuilder hashData = new StringBuilder();
            
            for (String fieldName : fieldNames) {
                String fieldValue = vnp_Params.get(fieldName);
                if ((fieldValue != null) && (fieldValue.length() > 0)) {
                    hashData.append(fieldName);
                    hashData.append('=');
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                    hashData.append('&');
                }
            }

            String signValue = HmacUtil.hmacSha512(secretKey, hashData.substring(0, hashData.length() - 1));
            return signValue.equals(vnp_SecureHash);
        } catch (Exception e) {
            log.error("Lỗi xác thực IPN VNPay: ", e);
            return false;
        }
    }
}
