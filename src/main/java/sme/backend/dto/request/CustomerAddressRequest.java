package sme.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomerAddressRequest {
    @NotBlank
    private String receiverName;
    
    @NotBlank
    private String receiverPhone;
    
    @NotBlank
    private String provinceCity;

    // Không còn bắt buộc: sau cải cách hành chính 2025, Việt Nam bỏ cấp quận/huyện
    private String district;

    private String ward;
    
    @NotBlank
    private String specificAddress;
    
    private Boolean isDefault;
}
