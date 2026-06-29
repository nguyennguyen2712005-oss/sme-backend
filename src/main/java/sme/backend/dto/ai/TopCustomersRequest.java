
package sme.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Yêu cầu lấy danh sách khách hàng thân thiết, chi tiêu nhiều nhất tại cửa hàng")
public record TopCustomersRequest(
        @JsonPropertyDescription("Số lượng khách hàng tối đa muốn xem, mặc định 5 nếu người dùng không nêu rõ")
        Integer limit
) {
}
