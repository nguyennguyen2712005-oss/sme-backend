package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sme.backend.entity.DiscountRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiscountRuleRepository extends JpaRepository<DiscountRule, UUID> {

    List<DiscountRule> findAllByOrderByCreatedAtDesc();

    @Query("""
        SELECT r FROM DiscountRule r
        WHERE r.isActive = true
          AND (r.warehouseId = :warehouseId OR r.warehouseId IS NULL)
        ORDER BY r.warehouseId DESC NULLS LAST
        LIMIT 1
    """)
    Optional<DiscountRule> findActiveRuleForWarehouse(@Param("warehouseId") UUID warehouseId);
}
