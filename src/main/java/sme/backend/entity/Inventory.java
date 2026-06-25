package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import sme.backend.exception.InsufficientStockException;

import java.util.UUID;

@Entity
@Table(name = "inventories", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"product_id", "warehouse_id"})
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    @Column(name = "in_transit", nullable = false)
    @Builder.Default
    private Integer inTransit = 0;

    @Column(name = "min_quantity", nullable = false)
    @Builder.Default
    private Integer minQuantity = 0;


    @Transient
    public int getAvailableQuantity() {
        return this.quantity - this.reservedQuantity;
    }

    @Transient
    public boolean isLowStock() {
        return this.minQuantity != null && this.minQuantity > 0 && this.quantity <= this.minQuantity;
    }

    public void deductPhysicalQuantity(int amount) {
        if (this.getAvailableQuantity() < amount) {
            throw new InsufficientStockException(
                    String.format("Không đủ tồn kho. Khả dụng: %d, Yêu cầu: %d",
                            this.getAvailableQuantity(), amount)
            );
        }
        this.quantity -= amount;
    }

    public void addQuantity(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Số lượng nhập phải > 0");
        }
        this.quantity += amount;
    }

    public void reserveQuantity(int amount) {
        if (this.getAvailableQuantity() < amount) {
            throw new InsufficientStockException(
                    String.format("Sản phẩm đã hết hàng trên Web. Khả dụng: %d, Yêu cầu: %d",
                            this.getAvailableQuantity(), amount)
            );
        }
        this.reservedQuantity += amount;
    }

    public void releaseReservedQuantity(int amount) {
        if (this.reservedQuantity < amount) {
            throw new IllegalStateException(
                    String.format("Lỗi logic: reserved=%d, giải phóng=%d", this.reservedQuantity, amount)
            );
        }
        this.reservedQuantity -= amount;
    }

    public void confirmShipment(int amount) {
        if (this.reservedQuantity < amount) {
            throw new IllegalStateException(
                    String.format("Lỗi logic: reserved=%d, xác nhận=%d", this.reservedQuantity, amount)
            );
        }
        this.quantity -= amount;
        this.reservedQuantity -= amount;
    }

    public void dispatchForTransfer(int amount) {
        if (this.getAvailableQuantity() < amount) {
            throw new InsufficientStockException(
                    "Không đủ tồn kho để chuyển kho. Khả dụng: " + this.getAvailableQuantity()
            );
        }
        this.quantity -= amount;
        this.inTransit += amount;
    }

    public void receiveTransfer(int amount) {
        this.inTransit -= amount;
        this.quantity += amount;
    }

    /** Hoàn hàng về kho xuất khi kho nhập từ chối toàn bộ (REJECTED_BY_RECEIVER) */
    public void reverseDispatch(int amount) {
        this.quantity += amount;
        this.inTransit = Math.max(0, this.inTransit - amount);
    }
}