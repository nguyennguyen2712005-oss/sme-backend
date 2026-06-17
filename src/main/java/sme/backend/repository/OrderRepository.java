package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByCode(String code);
    boolean existsByCode(String code);

    @Query("""
        SELECT o FROM Order o
        WHERE (:wid IS NULL OR o.assignedWarehouseId = :wid)
        AND o.status IN ('PENDING','PACKING')
        ORDER BY o.createdAt ASC
        """)
    List<Order> findPendingOrdersByWarehouse(@Param("wid") UUID warehouseId);

    @Query("""
        SELECT o FROM Order o
        WHERE o.assignedWarehouseId = :wid
        AND o.type = 'BOPIS'
        AND o.status = 'PACKING'
        ORDER BY o.createdAt ASC
        """)
    List<Order> findBOPISReadyByWarehouse(@Param("wid") UUID warehouseId);

    @EntityGraph(attributePaths = {"items"})
    Page<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    Page<Order> findByAssignedWarehouseIdAndStatusOrderByCreatedAtDesc(UUID warehouseId, Order.OrderStatus status, Pageable pageable);

    @Query("""
        SELECT o FROM Order o
        WHERE o.paymentMethod = 'COD'
        AND o.status = 'DELIVERED'
        AND o.codReconciled = false
        """)
    List<Order> findUnreconciledCODOrders();

    List<Order> findByAssignedWarehouseIdIsNullAndStatus(Order.OrderStatus status);

    @Query("""
        SELECT DISTINCT o FROM Order o
        LEFT JOIN FETCH o.items
        WHERE o.id = :id
        """)
    Optional<Order> findByIdWithDetails(@Param("id") UUID id);

    @Query("""
        SELECT o FROM Order o
        WHERE (:warehouseId IS NULL OR o.assignedWarehouseId = :warehouseId)
        AND (:status IS NULL OR o.status = :status)
        AND (:type IS NULL OR o.type = :type)
        AND (CAST(:fromDate AS java.time.Instant) IS NULL OR o.createdAt >= :fromDate)
        AND (CAST(:toDate AS java.time.Instant) IS NULL OR o.createdAt <= :toDate)
        AND (:paymentStatus IS NULL OR o.paymentStatus = :paymentStatus)
        AND (:paymentMethod IS NULL OR o.paymentMethod = :paymentMethod)
        AND (:provinceCode IS NULL OR o.provinceCode = :provinceCode
             OR (:provinceCode = '79' AND o.provinceCode = 'HCM')
             OR (:provinceCode = '01' AND o.provinceCode = 'HN')
             OR (:provinceCode = '48' AND o.provinceCode = 'DN')
             OR (:provinceCode = '92' AND o.provinceCode = 'CT')
             OR (:provinceCode = '31' AND o.provinceCode = 'HP')
             OR (:provinceCode = '74' AND o.provinceCode = 'BD')
             OR (:provinceCode = '77' AND o.provinceCode = 'BDT')
             OR (:provinceCode = '80' AND o.provinceCode = 'LA')
             OR (:provinceCode = '66' AND o.provinceCode = 'DL')
             OR (:provinceCode = '56' AND o.provinceCode = 'NT')
             OR (:provinceCode = '27' AND o.provinceCode = 'BN')
             OR (:provinceCode = '30' AND o.provinceCode = 'HB')
             OR (:provinceCode = '38' AND o.provinceCode = 'TH')
             OR (:provinceCode = '40' AND o.provinceCode = 'NA')
             OR (:provinceCode = '51' AND o.provinceCode = 'QNI')
             )
        AND (:keyword IS NULL OR :keyword = ''
             OR LOWER(o.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingPhone) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingName) LIKE LOWER(CONCAT('%', :keyword, '%'))
             )
        ORDER BY o.createdAt DESC
        """)
    Page<Order> searchOrders(@Param("warehouseId") UUID warehouseId,
                             @Param("status") Order.OrderStatus status,
                             @Param("type") Order.OrderType type,
                             @Param("keyword") String keyword,
                             @Param("fromDate") Instant fromDate,
                             @Param("toDate") Instant toDate,
                             @Param("paymentStatus") Order.PaymentStatus paymentStatus,
                             @Param("paymentMethod") String paymentMethod,
                             @Param("provinceCode") String provinceCode,
                             Pageable pageable);

    @Query("""
        SELECT 
            COUNT(o) AS totalCount,
            COALESCE(SUM(CASE WHEN o.status = :pendingStatus THEN 1 ELSE 0 END), 0) AS pendingCount,
            COALESCE(SUM(CASE WHEN o.paymentStatus = :paidStatus THEN 1 ELSE 0 END), 0) AS paidCount,
            COALESCE(SUM(CASE WHEN o.status <> :cancelledStatus THEN o.finalAmount ELSE 0.0 END), 0.0) AS totalRevenue
        FROM Order o
        WHERE (:warehouseId IS NULL OR o.assignedWarehouseId = :warehouseId)
        AND (:status IS NULL OR o.status = :status)
        AND (:type IS NULL OR o.type = :type)
        AND (CAST(:fromDate AS java.time.Instant) IS NULL OR o.createdAt >= :fromDate)
        AND (CAST(:toDate AS java.time.Instant) IS NULL OR o.createdAt <= :toDate)
        AND (:paymentStatus IS NULL OR o.paymentStatus = :paymentStatus)
        AND (:paymentMethod IS NULL OR o.paymentMethod = :paymentMethod)
        AND (:provinceCode IS NULL OR o.provinceCode = :provinceCode
             OR (:provinceCode = '79' AND o.provinceCode = 'HCM')
             OR (:provinceCode = '01' AND o.provinceCode = 'HN')
             OR (:provinceCode = '48' AND o.provinceCode = 'DN')
             OR (:provinceCode = '92' AND o.provinceCode = 'CT')
             OR (:provinceCode = '31' AND o.provinceCode = 'HP')
             OR (:provinceCode = '74' AND o.provinceCode = 'BD')
             OR (:provinceCode = '77' AND o.provinceCode = 'BDT')
             OR (:provinceCode = '80' AND o.provinceCode = 'LA')
             OR (:provinceCode = '66' AND o.provinceCode = 'DL')
             OR (:provinceCode = '56' AND o.provinceCode = 'NT')
             OR (:provinceCode = '27' AND o.provinceCode = 'BN')
             OR (:provinceCode = '30' AND o.provinceCode = 'HB')
             OR (:provinceCode = '38' AND o.provinceCode = 'TH')
             OR (:provinceCode = '40' AND o.provinceCode = 'NA')
             OR (:provinceCode = '51' AND o.provinceCode = 'QNI')
             )
        AND (:keyword IS NULL OR :keyword = ''
             OR LOWER(o.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingPhone) LIKE LOWER(CONCAT('%', :keyword, '%'))
             OR LOWER(o.shippingName) LIKE LOWER(CONCAT('%', :keyword, '%'))
             )
        """)
    java.util.Map<String, Object> getOrderStats(@Param("warehouseId") UUID warehouseId,
                                                @Param("status") Order.OrderStatus status,
                                                @Param("type") Order.OrderType type,
                                                @Param("keyword") String keyword,
                                                @Param("fromDate") Instant fromDate,
                                                @Param("toDate") Instant toDate,
                                                @Param("paymentStatus") Order.PaymentStatus paymentStatus,
                                                @Param("paymentMethod") String paymentMethod,
                                                @Param("provinceCode") String provinceCode,
                                                @Param("pendingStatus") Order.OrderStatus pendingStatus,
                                                @Param("paidStatus") Order.PaymentStatus paidStatus,
                                                @Param("cancelledStatus") Order.OrderStatus cancelledStatus);

    @Query("SELECT o FROM Order o WHERE o.paymentMethod != 'COD' AND o.paymentStatus = 'UNPAID' AND o.status = 'PAYMENT_PENDING' AND o.createdAt <= :expiryTime")
    org.springframework.data.domain.Slice<Order> findExpiredPendingOrders(@Param("expiryTime") Instant expiryTime, Pageable pageable);
}