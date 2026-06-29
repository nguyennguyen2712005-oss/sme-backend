
package sme.backend.dto.ai;

import com.fasterxml.jackson.annotation.JsonClassDescription;

@JsonClassDescription("Yêu cầu lấy báo cáo doanh thu, lợi nhuận của cửa hàng trong ngày hôm nay, tính trên tất cả chi nhánh")
public record TodayRevenueRequest() {
}
