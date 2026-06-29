
package sme.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Yêu cầu lấy danh sách các sản phẩm đang sắp hết hàng (tồn kho thấp dưới mức tối thiểu) ở tất cả chi nhánh, sắp xếp từ ít nhất")
public record LowStockRequest(
        @JsonPropertyDescription("Số lượng sản phẩm tối đa muốn xem, mặc định 10 nếu người dùng không nêu rõ")
        Integer limit
) {
}
