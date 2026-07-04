package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "supplier_return_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class SupplierReturnItem extends BaseSimpleEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_return_id", nullable = false)
    private SupplierReturn supplierReturn;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;

    /** DEFECTIVE | WRONG_ITEM | EXPIRED | OTHER */
    @Column(name = "return_reason", length = 100)
    private String returnReason;

    @PrePersist
    @PreUpdate
    public void computeSubtotal() {
        if (unitPrice != null && quantity != null) {
            this.subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
