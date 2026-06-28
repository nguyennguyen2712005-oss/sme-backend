package sme.backend.dto.response;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class InventoryTransactionResponse {
    private UUID id;
    private String transactionType;
    private int quantityChange;
    private int quantityBefore;
    private int quantityAfter;
    private String referenceCode;
    private String note;
    private Instant createdAt;
    private String createdBy;
}
