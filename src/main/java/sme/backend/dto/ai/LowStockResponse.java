
package sme.backend.dto.ai;

import java.util.List;

public record LowStockResponse(
        int totalLowStockItems,
        List<Item> items
) {
    public record Item(String productName, String warehouseName, int quantity, int minQuantity) {
    }
}
