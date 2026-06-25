package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.envers.Audited;
import org.hibernate.envers.AuditTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Entity
@Table(name = "products")
@Audited
@AuditTable("products_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product extends BaseEntity {

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "isbn_barcode", unique = true, nullable = false, length = 50)
    private String isbnBarcode;

    @Column(unique = true, length = 100)
    private String sku;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "retail_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal retailPrice;

    @Column(name = "wholesale_price", precision = 19, scale = 4)
    private BigDecimal wholesalePrice;

    @Column(name = "mac_price", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal macPrice = BigDecimal.ZERO;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(length = 50)
    @Builder.Default
    private String unit = "Cuốn";

    @Column(precision = 10, scale = 2)
    private BigDecimal weight;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_published")
    @Builder.Default
    private Boolean isPublished = false;

    // === THÔNG TIN SÁCH (BOOKLY) ===
    @Column(name = "cover_price", precision = 19, scale = 4)
    private BigDecimal coverPrice;

    @Column(name = "slug", unique = true, length = 300)
    private String slug;

    @Column(name = "publisher", length = 255)
    private String publisher;

    @Column(name = "publish_year")
    private Integer publishYear;

    @Column(name = "number_of_pages")
    private Integer numberOfPages;

    @Column(name = "dimensions", length = 50)
    private String dimensions;

    @Column(name = "cover_type", length = 20)
    private String coverType;

    @Column(name = "language", length = 50)
    @Builder.Default
    private String language = "Tiếng Việt";

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "author", length = 255)
    private String author;

    @Column(name = "average_rating")
    @Builder.Default
    private Double averageRating = 0.0;

    @Column(name = "total_reviews")
    @Builder.Default
    private Integer totalReviews = 0;

    @Column(name = "sold_quantity")
    @Builder.Default
    private Integer soldQuantity = 0;

    public void recalculateMAC(int currentQty, int importQty, BigDecimal importPrice) {
        if (importQty <= 0)
            return;

        if (this.macPrice == null) {
            this.macPrice = BigDecimal.ZERO;
        }

        BigDecimal totalExistingValue = this.macPrice.multiply(BigDecimal.valueOf(currentQty));
        BigDecimal totalNewValue = importPrice.multiply(BigDecimal.valueOf(importQty));
        BigDecimal totalQty = BigDecimal.valueOf((long) currentQty + importQty);

        if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
            this.macPrice = totalExistingValue
                    .add(totalNewValue)
                    .divide(totalQty, 4, RoundingMode.HALF_UP);
        }
    }
}