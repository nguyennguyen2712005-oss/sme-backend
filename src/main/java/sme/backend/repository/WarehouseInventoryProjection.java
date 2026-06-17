package sme.backend.repository;

import java.util.UUID;

public interface WarehouseInventoryProjection {
    UUID getWarehouseId();
    String getWarehouseName();
    UUID getInventoryId();
    Integer getQuantity();
    Integer getReservedQuantity();
    Integer getInTransit();
    Integer getMinQuantity();
}
