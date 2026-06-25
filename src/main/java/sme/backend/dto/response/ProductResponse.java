package sme.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data @Builder
@NoArgsConstructor @AllArgsConstructor
public class ProductResponse {
    private UUID id;
    private UUID categoryId;
    private String categoryName;
    private UUID supplierId;
    private String isbnBarcode;
    private String sku;
    private String name;
    private String description;
    private BigDecimal retailPrice;
    private BigDecimal wholesalePrice;
    private BigDecimal macPrice;
    private String imageUrl;
    private List<String> imageUrls;  
    private String unit;
    private BigDecimal weight;
    private Boolean isActive;
    private Boolean isPublished;
    private Instant createdAt;
    private Integer availableQuantity;

    private BigDecimal coverPrice;
    private String slug;
    private String publisher;
    private Integer publishYear;
    private Integer numberOfPages;
    private String dimensions;
    private String coverType;
    private String language;
    private UUID authorId;
    private String author;
    private Double averageRating;
    private Integer totalReviews;
    private Integer soldQuantity;
}