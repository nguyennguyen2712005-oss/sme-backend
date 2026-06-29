
package sme.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;

@JsonClassDescription("Yêu cầu lấy danh sách các chương trình khuyến mãi, mã giảm giá đang áp dụng cho khách mua hàng online tại cửa hàng")
public record ActivePromotionsRequest() {
}
