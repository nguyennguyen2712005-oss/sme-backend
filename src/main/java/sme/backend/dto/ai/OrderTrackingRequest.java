
package sme.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Yêu cầu tra cứu trạng thái một đơn hàng cụ thể theo mã đơn hàng mà khách hàng cung cấp")
public record OrderTrackingRequest(
        @JsonPropertyDescription("Mã đơn hàng khách cung cấp, ví dụ: ORD-1719500000000. Bắt buộc phải hỏi khách mã này nếu chưa có để tra cứu.")
        String orderCode
) {
}
