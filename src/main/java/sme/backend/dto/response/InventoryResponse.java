package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryResponse {
    private UUID id; // Có thể null nếu chưa từng nhập kho
    private UUID productId;
    private String productName;
    private String productSku;
    private String isbnBarcode;
    private String productImageUrl;
    private String categoryName;
    private String warehouseName;
    private int quantity;
    private int reservedQuantity;
    private int inTransit;
    private int minQuantity;
    private boolean lowStock;
    
    // Tính toán số lượng khả dụng
    public int getAvailableQuantity() {
        return this.quantity - this.reservedQuantity;
    }
}