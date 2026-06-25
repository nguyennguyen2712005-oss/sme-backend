package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.StockAdjustment;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {

    boolean existsByCode(String code);

    @Query("""
        SELECT a FROM StockAdjustment a LEFT JOIN FETCH a.items WHERE a.id = :id
        """)
    Optional<StockAdjustment> findByIdWithItems(@Param("id") UUID id);

    @Query("""
        SELECT a FROM StockAdjustment a
        WHERE (:warehouseId IS NULL OR a.warehouseId = :warehouseId)
        AND (:status IS NULL OR a.status = :status)
        AND (:keyword IS NULL OR :keyword = '' OR LOWER(a.code) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY a.createdAt DESC
        """)
    Page<StockAdjustment> search(
            @Param("warehouseId") UUID warehouseId,
            @Param("status") StockAdjustment.AdjustmentStatus status,
            @Param("keyword") String keyword,
            Pageable pageable);
}
