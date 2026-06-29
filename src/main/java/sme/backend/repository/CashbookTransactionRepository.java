package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.CashbookTransaction;

import sme.backend.dto.response.CashbookSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public interface CashbookTransactionRepository extends JpaRepository<CashbookTransaction, UUID> {

    List<CashbookTransaction> findByShiftIdOrderByCreatedAtAsc(UUID shiftId);

    List<CashbookTransaction> findByReferenceTypeAndReferenceIdOrderByCreatedAtDesc(
            String referenceType, UUID referenceId);

    List<CashbookTransaction> findByApprovalStatusOrderByCreatedAtDesc(
            CashbookTransaction.ApprovalStatus approvalStatus);

    List<CashbookTransaction> findByWarehouseIdAndApprovalStatusOrderByCreatedAtDesc(
            UUID warehouseId, CashbookTransaction.ApprovalStatus approvalStatus);

    Page<CashbookTransaction> findByWarehouseIdAndFundTypeOrderByCreatedAtDesc(
            UUID warehouseId,
            CashbookTransaction.FundType fundType,
            Pageable pageable);

    @Query("""
        SELECT SUM(
            CASE WHEN ct.transactionType = 'IN' THEN ct.amount
                 ELSE -ct.amount END
        )
        FROM CashbookTransaction ct
        WHERE ct.warehouseId = :wid
        AND ct.fundType = :fundType
        """)
    BigDecimal getCurrentBalanceByWarehouse(@Param("wid") UUID warehouseId,
                                            @Param("fundType") CashbookTransaction.FundType fundType);

    @Query("""
        SELECT SUM(
            CASE WHEN ct.transactionType = 'IN' THEN ct.amount
                 ELSE -ct.amount END
        )
        FROM CashbookTransaction ct
        WHERE ct.fundType = :fundType
        """)
    BigDecimal getCurrentBalanceAll(@Param("fundType") CashbookTransaction.FundType fundType);

    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.warehouseId = :wid
        AND ct.createdAt BETWEEN :from AND :to
        ORDER BY ct.createdAt DESC
        """)
    List<CashbookTransaction> findByWarehouseAndDateRange(
            @Param("wid")  UUID warehouseId,
            @Param("from") Instant from,
            @Param("to")   Instant to);

    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.createdAt BETWEEN :from AND :to
        ORDER BY ct.createdAt DESC
        """)
    List<CashbookTransaction> findAllByDateRange(
            @Param("from") Instant from,
            @Param("to")   Instant to);

    // =========================================================================
    // HÀM TÌM KIẾM CÓ PHÂN TRANG (Đã sửa lỗi PostgreSQL Null Parameter)
    // =========================================================================
    
    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.createdAt BETWEEN :from AND :to
        AND ct.fundType IN :fundTypes
        AND ct.transactionType IN :txnTypes
        AND (:keyword = '' OR LOWER(ct.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(ct.referenceType) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    Page<CashbookTransaction> searchCashbookAll(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("fundTypes") List<CashbookTransaction.FundType> fundTypes,
            @Param("txnTypes") List<CashbookTransaction.TransactionType> txnTypes,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("""
        SELECT ct FROM CashbookTransaction ct
        WHERE ct.warehouseId = :wid
        AND ct.createdAt BETWEEN :from AND :to
        AND ct.fundType IN :fundTypes
        AND ct.transactionType IN :txnTypes
        AND (:keyword = '' OR LOWER(ct.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(ct.referenceType) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    Page<CashbookTransaction> searchCashbookByWarehouse(
            @Param("wid") UUID warehouseId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("fundTypes") List<CashbookTransaction.FundType> fundTypes,
            @Param("txnTypes") List<CashbookTransaction.TransactionType> txnTypes,
            @Param("keyword") String keyword,
            Pageable pageable);

    // =========================================================================
    // SUMMARY QUERIES — tính SUM tại DB level, thay thế JS .reduce() sai ở frontend
    // Filters khớp hoàn toàn với search queries phía trên
    // =========================================================================

    @Query("""
        SELECT new sme.backend.dto.response.CashbookSummaryResponse(
            COALESCE(SUM(CASE WHEN ct.transactionType = 'IN' THEN ct.amount ELSE CAST(0 AS java.math.BigDecimal) END), CAST(0 AS java.math.BigDecimal)),
            COALESCE(SUM(CASE WHEN ct.transactionType = 'OUT' THEN ct.amount ELSE CAST(0 AS java.math.BigDecimal) END), CAST(0 AS java.math.BigDecimal))
        )
        FROM CashbookTransaction ct
        WHERE ct.createdAt BETWEEN :from AND :to
        AND ct.fundType IN :fundTypes
        AND ct.transactionType IN :txnTypes
        AND (:keyword = '' OR LOWER(ct.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(ct.referenceType) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    CashbookSummaryResponse summaryCashbookAll(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("fundTypes") List<CashbookTransaction.FundType> fundTypes,
            @Param("txnTypes") List<CashbookTransaction.TransactionType> txnTypes,
            @Param("keyword") String keyword);

    @Query("""
        SELECT new sme.backend.dto.response.CashbookSummaryResponse(
            COALESCE(SUM(CASE WHEN ct.transactionType = 'IN' THEN ct.amount ELSE CAST(0 AS java.math.BigDecimal) END), CAST(0 AS java.math.BigDecimal)),
            COALESCE(SUM(CASE WHEN ct.transactionType = 'OUT' THEN ct.amount ELSE CAST(0 AS java.math.BigDecimal) END), CAST(0 AS java.math.BigDecimal))
        )
        FROM CashbookTransaction ct
        WHERE ct.warehouseId = :wid
        AND ct.createdAt BETWEEN :from AND :to
        AND ct.fundType IN :fundTypes
        AND ct.transactionType IN :txnTypes
        AND (:keyword = '' OR LOWER(ct.description) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(ct.referenceType) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    CashbookSummaryResponse summaryCashbookByWarehouse(
            @Param("wid") UUID warehouseId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("fundTypes") List<CashbookTransaction.FundType> fundTypes,
            @Param("txnTypes") List<CashbookTransaction.TransactionType> txnTypes,
            @Param("keyword") String keyword);

    // =========================================================================
    // BUSINESS REPORT (OTHER INCOME / OTHER EXPENSES)
    // =========================================================================

    @Query(value = """
            SELECT
                DATE_TRUNC('day', ct.created_at) AS period,
                SUM(CASE WHEN ct.transaction_type = 'IN' AND ct.reference_type NOT IN ('INVOICE', 'ORDER') THEN ct.amount ELSE 0 END) AS other_income,
                SUM(CASE WHEN ct.transaction_type = 'OUT' THEN ct.amount ELSE 0 END) AS other_expenses
            FROM cashbook_transactions ct
            WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(ct.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
              AND ct.created_at BETWEEN :from AND :to
            GROUP BY DATE_TRUNC('day', ct.created_at)
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getOtherIncomeExpenseDaily(@Param("wid") UUID warehouseId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT
                DATE_TRUNC('hour', ct.created_at) AS period,
                SUM(CASE WHEN ct.transaction_type = 'IN' AND ct.reference_type NOT IN ('INVOICE', 'ORDER') THEN ct.amount ELSE 0 END) AS other_income,
                SUM(CASE WHEN ct.transaction_type = 'OUT' THEN ct.amount ELSE 0 END) AS other_expenses
            FROM cashbook_transactions ct
            WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(ct.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
              AND ct.created_at BETWEEN :from AND :to
            GROUP BY DATE_TRUNC('hour', ct.created_at)
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getOtherIncomeExpenseHourly(@Param("wid") UUID warehouseId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT
                DATE_TRUNC('month', ct.created_at) AS period,
                SUM(CASE WHEN ct.transaction_type = 'IN' AND ct.reference_type NOT IN ('INVOICE', 'ORDER') THEN ct.amount ELSE 0 END) AS other_income,
                SUM(CASE WHEN ct.transaction_type = 'OUT' THEN ct.amount ELSE 0 END) AS other_expenses
            FROM cashbook_transactions ct
            WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(ct.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
              AND ct.created_at BETWEEN :from AND :to
            GROUP BY DATE_TRUNC('month', ct.created_at)
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getOtherIncomeExpenseMonthly(@Param("wid") UUID warehouseId, @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
            SELECT
                DATE_TRUNC('year', ct.created_at) AS period,
                SUM(CASE WHEN ct.transaction_type = 'IN' AND ct.reference_type NOT IN ('INVOICE', 'ORDER') THEN ct.amount ELSE 0 END) AS other_income,
                SUM(CASE WHEN ct.transaction_type = 'OUT' THEN ct.amount ELSE 0 END) AS other_expenses
            FROM cashbook_transactions ct
            WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(ct.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
              AND ct.created_at BETWEEN :from AND :to
            GROUP BY DATE_TRUNC('year', ct.created_at)
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getOtherIncomeExpenseYearly(@Param("wid") UUID warehouseId, @Param("from") Instant from, @Param("to") Instant to);
}