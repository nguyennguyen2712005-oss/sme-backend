package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sme.backend.entity.SupplierReturn;

import java.util.Optional;
import java.util.UUID;

public interface SupplierReturnRepository extends JpaRepository<SupplierReturn, UUID> {

    @Query("SELECT sr FROM SupplierReturn sr LEFT JOIN FETCH sr.items WHERE sr.id = :id")
    Optional<SupplierReturn> findByIdWithItems(@Param("id") UUID id);

    @Query(value = """
            SELECT sr FROM SupplierReturn sr
            WHERE (:supplierId IS NULL OR sr.supplierId = :supplierId)
              AND (:warehouseId IS NULL OR sr.warehouseId = :warehouseId)
              AND (:status IS NULL OR sr.status = :status)
            ORDER BY sr.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(sr) FROM SupplierReturn sr
            WHERE (:supplierId IS NULL OR sr.supplierId = :supplierId)
              AND (:warehouseId IS NULL OR sr.warehouseId = :warehouseId)
              AND (:status IS NULL OR sr.status = :status)
            """)
    Page<SupplierReturn> search(
            @Param("supplierId") UUID supplierId,
            @Param("warehouseId") UUID warehouseId,
            @Param("status") SupplierReturn.ReturnStatus status,
            Pageable pageable);
}
