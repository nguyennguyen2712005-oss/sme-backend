package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreateProductRequest;
import sme.backend.dto.request.UpdateProductRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.dto.response.ProductResponse;
import sme.backend.dto.response.ProductReviewResponse;
import sme.backend.exception.BusinessException;
import sme.backend.security.UserPrincipal;
import sme.backend.service.ProductService;
import sme.backend.service.ProductReviewService;
import java.util.List;
import java.util.Map;
import sme.backend.service.AuditLogService;
import sme.backend.service.ExcelExportService;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductReviewService productReviewService;
    private final AuditLogService auditLogService;
    private final ExcelExportService excelExportService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ProductResponse>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID supplierId, // ĐÃ THÊM MỚI
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) Boolean isActive, 
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Sort sort = Sort.by("name");
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            switch (sortBy) {
                case "soldDesc":
                    sort = Sort.by(Sort.Order.desc("soldQuantity").nullsLast());
                    break;
                case "newest":
                    sort = Sort.by(Sort.Order.desc("createdAt").nullsLast());
                    break;
                case "priceAsc":
                    sort = Sort.by(Sort.Order.asc("retailPrice").nullsLast());
                    break;
                case "priceDesc":
                    sort = Sort.by(Sort.Order.desc("retailPrice").nullsLast());
                    break;
                default:
                    sort = Sort.by("name");
            }
        }
        
        var pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(productService.search(keyword, categoryId, supplierId, warehouseId, isActive, minPrice, maxPrice, minRating, pageable))));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) Double minRating) throws IOException {
        
        var pageable = PageRequest.of(0, 5000, Sort.by("name"));
        List<ProductResponse> products = productService.search(keyword, categoryId, supplierId, warehouseId, isActive, minPrice, maxPrice, minRating, pageable).getContent();
        
        byte[] excelBytes = excelExportService.exportProductsToExcel(products);

        return ResponseEntity.ok()
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=products.xlsx")
                .body(excelBytes);
    }

    @GetMapping("/barcode/{code}")
    @PreAuthorize("hasAnyRole('CASHIER','MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> getByBarcode(@PathVariable String code, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getByBarcode(code, principal.getWarehouseId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(productService.getById(id)));
    }

    @GetMapping("/{id}/reviews")
    public ResponseEntity<ApiResponse<PageResponse<ProductReviewResponse>>> getReviews(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer rating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(productReviewService.getReviewsByProduct(id, rating, pageable))));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> create(@Valid @RequestBody CreateProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(productService.createProduct(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> update(@PathVariable UUID id, @RequestBody UpdateProductRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(productService.updateProduct(id, req)));
    }

    @PostMapping("/{id}/images")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> addImage(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String imageUrl = body.get("imageUrl");
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "imageUrl không được để trống");
        }
        return ResponseEntity.ok(ApiResponse.ok(productService.addImage(id, imageUrl)));
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> deleteImage(
            @PathVariable UUID id,
            @PathVariable UUID imageId) {
        return ResponseEntity.ok(ApiResponse.ok(productService.deleteImage(id, imageId)));
    }

    @PutMapping("/{id}/images/reorder")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>> reorderImages(
            @PathVariable UUID id,
            @RequestBody List<UUID> orderedImageIds) {
        return ResponseEntity.ok(ApiResponse.ok(productService.reorderImages(id, orderedImageIds)));
    }

    @GetMapping("/{id}/price-history")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPriceHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(auditLogService.getProductPriceHistory(id)));
    }
}