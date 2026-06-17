package sme.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseInventoryItem {
    private UUID warehouseId;
    private String warehouseName;
    private UUID inventoryId; // Cần cho API updateMinQuantity sau này
    private int quantity;
    private int reservedQuantity;
    private int inTransit;
    private int availableQuantity;
    private int minQuantity;
    private boolean isLowStock;
}
