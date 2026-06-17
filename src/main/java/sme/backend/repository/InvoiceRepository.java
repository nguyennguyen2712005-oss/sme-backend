package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByCode(String code);

    boolean existsByCode(String code);

    @EntityGraph(attributePaths = {"items"})
    Page<Invoice> findByShiftIdOrderByCreatedAtDesc(UUID shiftId, Pageable pageable);

    @EntityGraph(attributePaths = {"items"})
    Page<Invoice> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    @Query(value = """
            SELECT i.* FROM invoices i
            WHERE (CAST(:shiftId AS UUID) IS NULL OR i.shift_id = CAST(:shiftId AS UUID))
              AND (CAST(:warehouseId AS UUID) IS NULL OR i.warehouse_id = CAST(:warehouseId AS UUID))
              AND (CAST(:type AS VARCHAR) IS NULL OR i.type = CAST(:type AS VARCHAR))
              AND (CAST(:keyword AS VARCHAR) IS NULL
                   OR LOWER(i.code) LIKE LOWER(CONCAT('%', CAST(:keyword AS VARCHAR), '%'))
                   OR (LOWER(CAST(:keyword AS VARCHAR)) = 'khách lẻ' AND i.customer_id IS NULL))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR i.created_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR i.created_at <= CAST(:to AS TIMESTAMPTZ))
              AND (CAST(:paymentMethod AS VARCHAR) IS NULL OR EXISTS (
                  SELECT 1 FROM invoice_payments ip WHERE ip.invoice_id = i.id AND CAST(ip.method AS VARCHAR) = CAST(:paymentMethod AS VARCHAR)
              ))
              AND (CAST(:cashierId AS UUID) IS NULL OR i.shift_id IN (SELECT s.id FROM shifts s WHERE s.cashier_id = CAST(:cashierId AS UUID)))
            ORDER BY i.created_at DESC
            """, countQuery = """
            SELECT COUNT(*) FROM invoices i
            WHERE (CAST(:shiftId AS UUID) IS NULL OR i.shift_id = CAST(:shiftId AS UUID))
              AND (CAST(:warehouseId AS UUID) IS NULL OR i.warehouse_id = CAST(:warehouseId AS UUID))
              AND (CAST(:type AS VARCHAR) IS NULL OR i.type = CAST(:type AS VARCHAR))
              AND (CAST(:keyword AS VARCHAR) IS NULL
                   OR LOWER(i.code) LIKE LOWER(CONCAT('%', CAST(:keyword AS VARCHAR), '%'))
                   OR (LOWER(CAST(:keyword AS VARCHAR)) = 'khách lẻ' AND i.customer_id IS NULL))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR i.created_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR i.created_at <= CAST(:to AS TIMESTAMPTZ))
              AND (CAST(:paymentMethod AS VARCHAR) IS NULL OR EXISTS (
                  SELECT 1 FROM invoice_payments ip WHERE ip.invoice_id = i.id AND CAST(ip.method AS VARCHAR) = CAST(:paymentMethod AS VARCHAR)
              ))
              AND (CAST(:cashierId AS UUID) IS NULL OR i.shift_id IN (SELECT s.id FROM shifts s WHERE s.cashier_id = CAST(:cashierId AS UUID)))
            """, nativeQuery = true)
    Page<Invoice> searchInvoices(
            @Param("shiftId") UUID shiftId,
            @Param("warehouseId") UUID warehouseId,
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("paymentMethod") String paymentMethod,
            @Param("cashierId") UUID cashierId,
            Pageable pageable);

    @Query("""
            SELECT DISTINCT i FROM Invoice i
            LEFT JOIN FETCH i.items
            WHERE i.id = :id
            """)
    Optional<Invoice> findByIdWithDetails(@Param("id") UUID id);

    @Query("""
            SELECT COALESCE(SUM(i.finalAmount), 0) FROM Invoice i
            WHERE i.shiftId = :shiftId AND i.type = 'SALE'
            """)
    BigDecimal sumRevenueByShift(@Param("shiftId") UUID shiftId);

    // ĐÃ SỬA: GỘP DOANH THU POS VÀ DOANH THU ĐƠN HÀNG ONLINE
    @Query(value = """
            SELECT
                period,
                COUNT(DISTINCT doc_id) AS invoice_count,
                SUM(revenue) AS revenue,
                SUM(cogs) AS cogs,
                SUM(revenue) - SUM(cogs) AS gross_profit
            FROM (
                SELECT
                    DATE_TRUNC('day', i.created_at) AS period,
                    i.id AS doc_id,
                    i.final_amount AS revenue,
                    COALESCE(item_agg.total_cogs, 0) AS cogs
                FROM invoices i
                JOIN shifts s ON s.id = i.shift_id
                LEFT JOIN (
                    SELECT invoice_id, SUM(quantity * mac_price) AS total_cogs
                    FROM invoice_items GROUP BY invoice_id
                ) item_agg ON item_agg.invoice_id = i.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(s.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND i.type = 'SALE'
                  AND (CAST(:cid AS UUID) IS NULL OR s.cashier_id = CAST(:cid AS UUID))
                  AND i.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR EXISTS (SELECT 1 FROM invoice_payments ip WHERE ip.invoice_id = i.id AND CAST(ip.method AS VARCHAR) = CAST(:pm AS VARCHAR)))

                UNION ALL

                SELECT
                    DATE_TRUNC('day', o.created_at) AS period,
                    o.id AS doc_id,
                    o.final_amount AS revenue,
                    COALESCE(ord_item_agg.total_cogs, 0) AS cogs
                FROM orders o
                LEFT JOIN (
                    SELECT order_id, SUM(quantity * mac_price) AS total_cogs
                    FROM order_items GROUP BY order_id
                ) ord_item_agg ON ord_item_agg.order_id = o.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(o.assigned_warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND o.status = 'DELIVERED'
                  AND (CAST(:cid AS VARCHAR) IS NULL OR CAST(o.created_by AS VARCHAR) = CAST(:cid AS VARCHAR))
                  AND o.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR o.payment_method = CAST(:pm AS VARCHAR))
            ) combined_data
            GROUP BY period
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getRevenueReportDaily(@Param("wid") UUID warehouseId, @Param("from") Instant from,
            @Param("to") Instant to, @Param("pm") String paymentMethod, @Param("cid") UUID cashierId);

    @Query(value = """
            SELECT
                period,
                COUNT(DISTINCT doc_id) AS invoice_count,
                SUM(revenue) AS revenue,
                SUM(cogs) AS cogs,
                SUM(revenue) - SUM(cogs) AS gross_profit
            FROM (
                SELECT
                    DATE_TRUNC('hour', i.created_at) AS period,
                    i.id AS doc_id,
                    i.final_amount AS revenue,
                    COALESCE(item_agg.total_cogs, 0) AS cogs
                FROM invoices i
                JOIN shifts s ON s.id = i.shift_id
                LEFT JOIN (
                    SELECT invoice_id, SUM(quantity * mac_price) AS total_cogs
                    FROM invoice_items GROUP BY invoice_id
                ) item_agg ON item_agg.invoice_id = i.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(s.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND i.type = 'SALE'
                  AND (CAST(:cid AS UUID) IS NULL OR s.cashier_id = CAST(:cid AS UUID))
                  AND i.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR EXISTS (SELECT 1 FROM invoice_payments ip WHERE ip.invoice_id = i.id AND CAST(ip.method AS VARCHAR) = CAST(:pm AS VARCHAR)))

                UNION ALL

                SELECT
                    DATE_TRUNC('hour', o.created_at) AS period,
                    o.id AS doc_id,
                    o.final_amount AS revenue,
                    COALESCE(ord_item_agg.total_cogs, 0) AS cogs
                FROM orders o
                LEFT JOIN (
                    SELECT order_id, SUM(quantity * mac_price) AS total_cogs
                    FROM order_items GROUP BY order_id
                ) ord_item_agg ON ord_item_agg.order_id = o.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(o.assigned_warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND o.status = 'DELIVERED'
                  AND (CAST(:cid AS VARCHAR) IS NULL OR CAST(o.created_by AS VARCHAR) = CAST(:cid AS VARCHAR))
                  AND o.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR o.payment_method = CAST(:pm AS VARCHAR))
            ) combined_data
            GROUP BY period
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getRevenueReportHourly(@Param("wid") UUID warehouseId, @Param("from") Instant from,
            @Param("to") Instant to, @Param("pm") String paymentMethod, @Param("cid") UUID cashierId);

    @Query(value = """
            SELECT
                period,
                COUNT(DISTINCT doc_id) AS invoice_count,
                SUM(revenue) AS revenue,
                SUM(cogs) AS cogs,
                SUM(revenue) - SUM(cogs) AS gross_profit
            FROM (
                SELECT
                    DATE_TRUNC('week', i.created_at) AS period,
                    i.id AS doc_id,
                    i.final_amount AS revenue,
                    COALESCE(item_agg.total_cogs, 0) AS cogs
                FROM invoices i
                JOIN shifts s ON s.id = i.shift_id
                LEFT JOIN (
                    SELECT invoice_id, SUM(quantity * mac_price) AS total_cogs
                    FROM invoice_items GROUP BY invoice_id
                ) item_agg ON item_agg.invoice_id = i.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(s.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND i.type = 'SALE'
                  AND (CAST(:cid AS UUID) IS NULL OR s.cashier_id = CAST(:cid AS UUID))
                  AND i.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR EXISTS (SELECT 1 FROM invoice_payments ip WHERE ip.invoice_id = i.id AND CAST(ip.method AS VARCHAR) = CAST(:pm AS VARCHAR)))

                UNION ALL

                SELECT
                    DATE_TRUNC('week', o.created_at) AS period,
                    o.id AS doc_id,
                    o.final_amount AS revenue,
                    COALESCE(ord_item_agg.total_cogs, 0) AS cogs
                FROM orders o
                LEFT JOIN (
                    SELECT order_id, SUM(quantity * mac_price) AS total_cogs
                    FROM order_items GROUP BY order_id
                ) ord_item_agg ON ord_item_agg.order_id = o.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(o.assigned_warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND o.status = 'DELIVERED'
                  AND (CAST(:cid AS VARCHAR) IS NULL OR CAST(o.created_by AS VARCHAR) = CAST(:cid AS VARCHAR))
                  AND o.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR o.payment_method = CAST(:pm AS VARCHAR))
            ) combined_data
            GROUP BY period
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getRevenueReportWeekly(@Param("wid") UUID warehouseId, @Param("from") Instant from,
            @Param("to") Instant to, @Param("pm") String paymentMethod, @Param("cid") UUID cashierId);

    @Query(value = """
            SELECT
                period,
                COUNT(DISTINCT doc_id) AS invoice_count,
                SUM(revenue) AS revenue,
                SUM(cogs) AS cogs,
                SUM(revenue) - SUM(cogs) AS gross_profit
            FROM (
                SELECT
                    DATE_TRUNC('month', i.created_at) AS period,
                    i.id AS doc_id,
                    i.final_amount AS revenue,
                    COALESCE(item_agg.total_cogs, 0) AS cogs
                FROM invoices i
                JOIN shifts s ON s.id = i.shift_id
                LEFT JOIN (
                    SELECT invoice_id, SUM(quantity * mac_price) AS total_cogs
                    FROM invoice_items GROUP BY invoice_id
                ) item_agg ON item_agg.invoice_id = i.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(s.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND i.type = 'SALE'
                  AND (CAST(:cid AS UUID) IS NULL OR s.cashier_id = CAST(:cid AS UUID))
                  AND i.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR EXISTS (SELECT 1 FROM invoice_payments ip WHERE ip.invoice_id = i.id AND CAST(ip.method AS VARCHAR) = CAST(:pm AS VARCHAR)))

                UNION ALL

                SELECT
                    DATE_TRUNC('month', o.created_at) AS period,
                    o.id AS doc_id,
                    o.final_amount AS revenue,
                    COALESCE(ord_item_agg.total_cogs, 0) AS cogs
                FROM orders o
                LEFT JOIN (
                    SELECT order_id, SUM(quantity * mac_price) AS total_cogs
                    FROM order_items GROUP BY order_id
                ) ord_item_agg ON ord_item_agg.order_id = o.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(o.assigned_warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND o.status = 'DELIVERED'
                  AND (CAST(:cid AS VARCHAR) IS NULL OR CAST(o.created_by AS VARCHAR) = CAST(:cid AS VARCHAR))
                  AND o.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR o.payment_method = CAST(:pm AS VARCHAR))
            ) combined_data
            GROUP BY period
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getRevenueReportMonthly(@Param("wid") UUID warehouseId, @Param("from") Instant from,
            @Param("to") Instant to, @Param("pm") String paymentMethod, @Param("cid") UUID cashierId);

    @Query(value = """
            SELECT
                period,
                COUNT(DISTINCT doc_id) AS invoice_count,
                SUM(revenue) AS revenue,
                SUM(cogs) AS cogs,
                SUM(revenue) - SUM(cogs) AS gross_profit
            FROM (
                SELECT
                    DATE_TRUNC('year', i.created_at) AS period,
                    i.id AS doc_id,
                    i.final_amount AS revenue,
                    COALESCE(item_agg.total_cogs, 0) AS cogs
                FROM invoices i
                JOIN shifts s ON s.id = i.shift_id
                LEFT JOIN (
                    SELECT invoice_id, SUM(quantity * mac_price) AS total_cogs
                    FROM invoice_items GROUP BY invoice_id
                ) item_agg ON item_agg.invoice_id = i.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(s.warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND i.type = 'SALE'
                  AND (CAST(:cid AS UUID) IS NULL OR s.cashier_id = CAST(:cid AS UUID))
                  AND i.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR EXISTS (SELECT 1 FROM invoice_payments ip WHERE ip.invoice_id = i.id AND CAST(ip.method AS VARCHAR) = CAST(:pm AS VARCHAR)))

                UNION ALL

                SELECT
                    DATE_TRUNC('year', o.created_at) AS period,
                    o.id AS doc_id,
                    o.final_amount AS revenue,
                    COALESCE(ord_item_agg.total_cogs, 0) AS cogs
                FROM orders o
                LEFT JOIN (
                    SELECT order_id, SUM(quantity * mac_price) AS total_cogs
                    FROM order_items GROUP BY order_id
                ) ord_item_agg ON ord_item_agg.order_id = o.id
                WHERE (CAST(:wid AS VARCHAR) IS NULL OR CAST(o.assigned_warehouse_id AS VARCHAR) = CAST(:wid AS VARCHAR))
                  AND o.status = 'DELIVERED'
                  AND (CAST(:cid AS VARCHAR) IS NULL OR CAST(o.created_by AS VARCHAR) = CAST(:cid AS VARCHAR))
                  AND o.created_at BETWEEN :from AND :to
                  AND (CAST(:pm AS VARCHAR) IS NULL OR o.payment_method = CAST(:pm AS VARCHAR))
            ) combined_data
            GROUP BY period
            ORDER BY period
            """, nativeQuery = true)
    List<Map<String, Object>> getRevenueReportYearly(@Param("wid") UUID warehouseId, @Param("from") Instant from,
            @Param("to") Instant to, @Param("pm") String paymentMethod, @Param("cid") UUID cashierId);

    @Query(value = """
            SELECT
                COUNT(i.id) AS totalCount,
                CAST(0 AS bigint) AS pendingCount,
                CAST(COALESCE(SUM(CASE WHEN i.type = 'SALE' THEN 1 ELSE 0 END), 0) AS bigint) AS paidCount,
                COALESCE(SUM(CASE WHEN i.type = 'SALE' THEN i.final_amount
                                  WHEN i.type = 'RETURN' THEN -i.final_amount
                                  ELSE 0 END), 0) AS totalRevenue
            FROM invoices i
            WHERE (CAST(:warehouseId AS UUID) IS NULL OR i.warehouse_id = CAST(:warehouseId AS UUID))
              AND (CAST(:type AS VARCHAR) IS NULL OR i.type = CAST(:type AS VARCHAR))
              AND (CAST(:keyword AS VARCHAR) IS NULL
                   OR LOWER(i.code) LIKE LOWER(CONCAT('%', CAST(:keyword AS VARCHAR), '%'))
                   OR (LOWER(CAST(:keyword AS VARCHAR)) = 'khách lẻ' AND i.customer_id IS NULL))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR i.created_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR i.created_at <= CAST(:to AS TIMESTAMPTZ))
              AND (CAST(:cashierId AS UUID) IS NULL OR i.shift_id IN (SELECT s.id FROM shifts s WHERE s.cashier_id = CAST(:cashierId AS UUID)))
            """, nativeQuery = true)
    Map<String, Object> getInvoiceStats(@Param("warehouseId") UUID warehouseId,
                                        @Param("type") String type,
                                        @Param("keyword") String keyword,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        @Param("cashierId") UUID cashierId);
}