package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Customer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    // POS: Định danh khách hàng qua SĐT (F3)
    Optional<Customer> findByPhoneNumberAndIsActiveTrue(String phoneNumber);

    Optional<Customer> findByPhoneNumber(String phoneNumber);

    Optional<Customer> findByUserId(UUID userId);

    boolean existsByPhoneNumber(String phoneNumber);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.createdAt >= :fromDate AND c.createdAt <= :toDate")
    long countNewCustomers(@Param("fromDate") java.time.Instant fromDate, @Param("toDate") java.time.Instant toDate);

    // ĐÃ NÂNG CẤP: Gộp cả tìm kiếm keyword và lọc theo hạng thẻ (tier) vào một Query linh hoạt
    @Query("SELECT c FROM Customer c WHERE c.isActive = true " +
           "AND (:tier IS NULL OR c.customerTier = :tier) " +
           "AND (:kw IS NULL OR :kw = '' " +
           "OR LOWER(c.fullName) LIKE LOWER(CONCAT('%', :kw, '%')) " +
           "OR c.phoneNumber LIKE CONCAT('%', REPLACE(:kw, ' ', ''), '%') " +
           "OR LOWER(c.email) LIKE LOWER(CONCAT('%', :kw, '%')))")
    Page<Customer> searchWithFilters(@Param("kw") String keyword, 
                                     @Param("tier") Customer.CustomerTier tier, 
                                     Pageable pageable);

    // Top khách hàng theo tổng chi tiêu
    @Query("""
        SELECT c FROM Customer c
        WHERE c.isActive = true
        ORDER BY c.totalSpent DESC
        """)
    Page<Customer> findTopCustomers(Pageable pageable);

    @Query(value = """
        SELECT c.full_name as fullName, 
               CAST(COALESCE(SUM(combined.order_count), 0) AS INTEGER) as totalOrders, 
               CAST(COALESCE(SUM(combined.revenue), 0) AS NUMERIC) as totalPurchase
        FROM customers c
        JOIN (
            SELECT customer_id, 1 AS order_count, final_amount AS revenue
            FROM invoices
            WHERE created_at BETWEEN :fromDate AND :toDate
              AND type = 'SALE'
              AND customer_id IS NOT NULL
              AND (CAST(:warehouseId AS VARCHAR) IS NULL OR CAST(warehouse_id AS VARCHAR) = CAST(:warehouseId AS VARCHAR))
            UNION ALL
            SELECT customer_id, 1 AS order_count, final_amount AS revenue
            FROM orders
            WHERE created_at BETWEEN :fromDate AND :toDate
              AND status = 'DELIVERED'
              AND customer_id IS NOT NULL
              AND (CAST(:warehouseId AS VARCHAR) IS NULL OR CAST(assigned_warehouse_id AS VARCHAR) = CAST(:warehouseId AS VARCHAR))
        ) combined ON c.id = combined.customer_id
        WHERE c.is_active = true
        GROUP BY c.id, c.full_name
        ORDER BY 
            CASE WHEN :metric = 'orders' THEN CAST(COALESCE(SUM(combined.order_count), 0) AS NUMERIC)
                 ELSE CAST(COALESCE(SUM(combined.revenue), 0) AS NUMERIC) END DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Map<String, Object>> findTopCustomersByTimeRange(
            @Param("warehouseId") UUID warehouseId,
            @Param("fromDate") java.time.Instant fromDate,
            @Param("toDate") java.time.Instant toDate,
            @Param("metric") String metric,
            @Param("limit") int limit);
}