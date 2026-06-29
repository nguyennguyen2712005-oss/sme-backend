package sme.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class CreateCashbookEntryRequest {

    @NotNull(message = "warehouseId bắt buộc")
    private UUID warehouseId;

    @NotBlank(message = "Loại quỹ không được để trống")
    private String fundType;          // CASH_111, BANK_112

    @NotBlank(message = "Loại giao dịch không được để trống")
    private String transactionType;   // IN, OUT

    @NotBlank(message = "Loại chứng từ không được để trống")
    private String referenceType;     // EXPENSE, OTHER_INCOME, MANUAL

    @NotNull
    @DecimalMin(value = "0.01", message = "Số tiền phải > 0")
    private BigDecimal amount;

    @NotBlank(message = "Mô tả không được để trống")
    private String description;

    private String personName;
}
