package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.dto.response.SupplierDebtSummaryResponse;
import sme.backend.entity.SupplierDebt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupplierDebtRepository extends JpaRepository<SupplierDebt, UUID> {

    Optional<SupplierDebt> findByPurchaseOrderId(UUID purchaseOrderId);

    List<SupplierDebt> findBySupplierIdAndStatusNot(UUID supplierId, SupplierDebt.DebtStatus status);
    
    List<SupplierDebt> findByStatus(SupplierDebt.DebtStatus status);

    // BỔ SUNG DÒNG NÀY ĐỂ TÌM CÁC CÔNG NỢ CHƯA TRẢ HOẶC TRẢ MỘT PHẦN
    List<SupplierDebt> findByStatusNot(SupplierDebt.DebtStatus status);

    @Query("""
        SELECT COALESCE(SUM(sd.totalDebt - sd.paidAmount), 0)
        FROM SupplierDebt sd
        WHERE sd.supplierId = :sid
        AND sd.status != 'PAID'
        """)
    BigDecimal getTotalOutstandingBySupplierId(@Param("sid") UUID supplierId);

    @Query("SELECT sd FROM SupplierDebt sd WHERE sd.status != 'PAID' " +
           "AND (:warehouseId IS NULL OR sd.purchaseOrderId IN " +
           "(SELECT po.id FROM PurchaseOrder po WHERE po.warehouseId = :warehouseId))")
    List<SupplierDebt> findOutstandingDebtsByWarehouse(@Param("warehouseId") UUID warehouseId);

    // =========================================================================
    // SEARCH CÓ PHÂN TRANG — thay thế fetch-all ở frontend
    // =========================================================================

    @Query("""
        SELECT sd FROM SupplierDebt sd
        LEFT JOIN Supplier s ON s.id = sd.supplierId
        WHERE sd.status != 'PAID'
        AND (:warehouseId IS NULL OR sd.purchaseOrderId IN
            (SELECT po.id FROM PurchaseOrder po WHERE po.warehouseId = :warehouseId))
        AND (:search IS NULL OR :search = ''
            OR LOWER(s.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(CAST(sd.purchaseOrderId AS string)) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY sd.createdAt DESC
        """)
    Page<SupplierDebt> searchOutstandingDebts(
            @Param("warehouseId") UUID warehouseId,
            @Param("search") String search,
            Pageable pageable);

    // =========================================================================
    // SUMMARY QUERY — tính tổng hợp tại DB level, thay thế JS .reduce() sai ở frontend
    // Filters khớp hoàn toàn với search query phía trên
    // =========================================================================

    @Query("""
        SELECT new sme.backend.dto.response.SupplierDebtSummaryResponse(
            COALESCE(SUM(CASE WHEN sd.status != 'PAID' THEN sd.totalDebt
                         ELSE CAST(0 AS java.math.BigDecimal) END),
                     CAST(0 AS java.math.BigDecimal)),
            COALESCE(SUM(sd.paidAmount), CAST(0 AS java.math.BigDecimal)),
            COALESCE(SUM(CASE WHEN sd.status != 'PAID' THEN (sd.totalDebt - sd.paidAmount)
                         ELSE CAST(0 AS java.math.BigDecimal) END),
                     CAST(0 AS java.math.BigDecimal)),
            COUNT(DISTINCT CASE WHEN sd.status != 'PAID'
                                AND (sd.totalDebt - sd.paidAmount) > 0
                           THEN sd.supplierId ELSE NULL END)
        )
        FROM SupplierDebt sd
        LEFT JOIN Supplier s ON s.id = sd.supplierId
        WHERE (:warehouseId IS NULL OR sd.purchaseOrderId IN
            (SELECT po.id FROM PurchaseOrder po WHERE po.warehouseId = :warehouseId))
        AND (:search IS NULL OR :search = ''
            OR LOWER(s.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR LOWER(CAST(sd.purchaseOrderId AS string)) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    SupplierDebtSummaryResponse getSupplierDebtSummary(
            @Param("warehouseId") UUID warehouseId,
            @Param("search") String search);
}