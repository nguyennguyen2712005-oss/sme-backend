package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.ProductReview;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID> {

    Page<ProductReview> findByProductIdAndIsApprovedTrueOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    @Query("SELECT r FROM ProductReview r WHERE r.productId = :productId AND r.isApproved = true AND (:rating IS NULL OR r.rating = :rating) ORDER BY r.createdAt DESC")
    Page<ProductReview> findApprovedReviewsWithRating(@Param("productId") UUID productId, @Param("rating") Integer rating, Pageable pageable);

    boolean existsByProductIdAndCustomerIdAndOrderId(UUID productId, UUID customerId, UUID orderId);

    List<ProductReview> findByProductIdInAndCustomerIdAndOrderId(java.util.List<UUID> productIds, UUID customerId, UUID orderId);

    Optional<ProductReview> findByProductIdAndCustomerIdAndOrderId(UUID productId, UUID customerId, UUID orderId);

    @Query("SELECT AVG(r.rating) FROM ProductReview r WHERE r.productId = :productId AND r.isApproved = true")
    Double getAverageRatingByProductId(@Param("productId") UUID productId);

    Long countByProductIdAndIsApprovedTrue(UUID productId);

    @Query("SELECT r FROM ProductReview r WHERE " +
           "(:status IS NULL OR r.isApproved = :status) " +
           "ORDER BY r.createdAt DESC")
    Page<ProductReview> findAllForAdmin(@Param("status") Boolean status, Pageable pageable);
}
