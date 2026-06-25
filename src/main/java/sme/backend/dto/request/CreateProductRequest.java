package sme.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class CreateProductRequest {

    @NotNull(message = "Danh mục không được để trống")
    private UUID categoryId;

    private UUID supplierId;

    @NotBlank(message = "Mã vạch/ISBN không được để trống")
    @Size(max = 50)
    private String isbnBarcode;

    @Size(max = 100)
    private String sku;

    @NotBlank(message = "Tên sản phẩm không được để trống")
    @Size(max = 255)
    private String name;

    private String description;

    @NotNull(message = "Giá bán lẻ không được để trống")
    @DecimalMin(value = "0", message = "Giá bán lẻ không được âm")
    private BigDecimal retailPrice;

    private BigDecimal wholesalePrice;

    private String imageUrl;
    private List<String> imageUrls;
    private String unit;

    @DecimalMin(value = "0")
    private BigDecimal weight;

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

    private Boolean isPublished;

    // Trường dùng cho Bulk Import để lưu lại số dòng gốc trong Excel
    private Integer importRowIndex;

}
