package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByIsbnBarcodeIgnoreCaseAndIsActiveTrue(String isbnBarcode);
    Optional<Product> findBySkuIgnoreCaseAndIsActiveTrue(String sku);
    boolean existsByIsbnBarcode(String isbnBarcode);

    @Query("""
        SELECT p FROM Product p
        WHERE p.isActive = true
        AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR p.isbnBarcode LIKE CONCAT('%', :keyword, '%')
          OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY p.name
        """)
    Page<Product> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    Page<Product> findByCategoryIdAndIsActiveTrue(UUID categoryId, Pageable pageable);

    @Modifying
    @Query("UPDATE Product p SET p.macPrice = :macPrice WHERE p.id = :id")
    void updateMacPrice(@Param("id") UUID id, @Param("macPrice") BigDecimal macPrice);

    // NV-2: Atomic update for soldQuantity without @Version to avoid OptimisticLockException
    // clearAutomatically = true ensures any subsequent reads in the same transaction fetch the fresh value.
    @Modifying
    @Query("UPDATE Product p SET p.soldQuantity = COALESCE(p.soldQuantity, 0) + :qty WHERE p.id = :id")
    void incrementSoldQuantity(@Param("id") UUID id, @Param("qty") int qty);

    @Modifying
    @Query("UPDATE Product p SET p.soldQuantity = GREATEST(COALESCE(p.soldQuantity, 0) - :qty, 0) WHERE p.id = :id")
    void decrementSoldQuantity(@Param("id") UUID id, @Param("qty") int qty);

    @Query(value = """
        SELECT p.* FROM products p
        WHERE p.is_active = true
        AND NOT EXISTS (
            SELECT 1 FROM product_vectors pv WHERE pv.product_id = p.id
        )
        """, nativeQuery = true)
    List<Product> findProductsWithoutEmbedding();

    // ĐÃ SỬA: GỘP SỐ LƯỢNG BÁN TỪ CẢ POS VÀ ONLINE
    @Query(value = """
        SELECT p.id as id, p.name as name, 
               CAST(COALESCE(SUM(combined.qty), 0) AS INTEGER) as total_sold, 
               CAST(COALESCE(SUM(combined.revenue), 0) AS NUMERIC) as total_revenue
        FROM products p
        JOIN (
            SELECT ii.product_id, ii.quantity AS qty, ii.subtotal AS revenue
            FROM invoice_items ii
            JOIN invoices i ON ii.invoice_id = i.id
            WHERE i.created_at BETWEEN :fromDate AND :toDate
              AND (CAST(:warehouseId AS VARCHAR) IS NULL OR CAST(i.warehouse_id AS VARCHAR) = CAST(:warehouseId AS VARCHAR))
              AND i.type = 'SALE'

            UNION ALL

            SELECT oi.product_id, oi.quantity AS qty, oi.subtotal AS revenue
            FROM order_items oi
            JOIN orders o ON oi.order_id = o.id
            WHERE o.created_at BETWEEN :fromDate AND :toDate
              AND (CAST(:warehouseId AS VARCHAR) IS NULL OR CAST(o.assigned_warehouse_id AS VARCHAR) = CAST(:warehouseId AS VARCHAR))
              AND o.status = 'DELIVERED'
        ) combined ON p.id = combined.product_id
        WHERE p.is_active = true
        GROUP BY p.id, p.name
        ORDER BY total_sold DESC, total_revenue DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Map<String, Object>> findTopSellingProducts(
            @Param("warehouseId") UUID warehouseId,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            @Param("limit") int limit,
            @Param("pm") String paymentMethod);
            
    @Query("""
            SELECT p FROM Product p
            WHERE (:categoryId IS NULL OR p.categoryId IN (SELECT c.id FROM Category c WHERE c.id = :categoryId OR c.parentId = :categoryId))
            AND (:supplierId IS NULL OR p.supplierId = :supplierId)
            AND (:isActive IS NULL OR p.isActive = :isActive)
            AND (:isPublished IS NULL OR p.isPublished = :isPublished)
            AND (:minPrice IS NULL OR p.retailPrice >= :minPrice)
            AND (:maxPrice IS NULL OR p.retailPrice <= :maxPrice)
            AND (:minRating IS NULL OR p.averageRating >= :minRating)
            AND (:keyword IS NULL OR :keyword = ''
            OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR p.slug LIKE LOWER(CONCAT('%', :slugKeyword, '%'))
            OR LOWER(p.isbnBarcode) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Product> searchProducts(@Param("keyword") String keyword,
                                 @Param("slugKeyword") String slugKeyword,
                                 @Param("categoryId") UUID categoryId,
                                 @Param("supplierId") UUID supplierId,
                                 @Param("isActive") Boolean isActive,
                                 @Param("isPublished") Boolean isPublished,
                                 @Param("minPrice") Double minPrice,
                                 @Param("maxPrice") Double maxPrice,
                                 @Param("minRating") Double minRating,
                                 Pageable pageable);
}