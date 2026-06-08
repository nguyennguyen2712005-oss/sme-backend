package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreateReviewRequest;
import sme.backend.dto.response.ProductReviewResponse;
import sme.backend.entity.Customer;
import sme.backend.entity.Order;
import sme.backend.entity.Product;
import sme.backend.entity.ProductReview;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.CustomerRepository;
import sme.backend.repository.OrderRepository;
import sme.backend.repository.ProductRepository;
import sme.backend.repository.ProductReviewRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductReviewService {

    private final ProductReviewRepository productReviewRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public Page<ProductReviewResponse> getReviewsByProduct(UUID productId, Integer rating, Pageable pageable) {
        return productReviewRepository.findApprovedReviewsWithRating(productId, rating, pageable)
                .map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductReviewResponse> getAllReviews(Boolean status, Pageable pageable) {
        return productReviewRepository.findAllForAdmin(status, pageable)
                .map(this::mapToResponse);
    }

    @Transactional
    public ProductReviewResponse createReview(UUID customerId, CreateReviewRequest req) {
        // Validate Order
        Order order = orderRepository.findById(req.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", req.getOrderId()));
        if (!order.getCustomerId().equals(customerId)) {
            throw new BusinessException("INVALID_ORDER", "Đơn hàng không thuộc về khách hàng này");
        }
        if (order.getStatus() != Order.OrderStatus.DELIVERED) {
            throw new BusinessException("INVALID_ORDER_STATUS", "Chỉ có thể đánh giá khi đơn hàng đã giao thành công");
        }
        boolean hasProduct = order.getItems().stream()
                .anyMatch(item -> item.getProductId().equals(req.getProductId()));
        if (!hasProduct) {
            throw new BusinessException("INVALID_PRODUCT", "Đơn hàng không chứa sản phẩm này");
        }

        // Validate if already reviewed
        if (productReviewRepository.existsByProductIdAndCustomerIdAndOrderId(req.getProductId(), customerId, req.getOrderId())) {
            throw new BusinessException("ALREADY_REVIEWED", "Bạn đã đánh giá sản phẩm này trong đơn hàng này rồi");
        }

        ProductReview review = ProductReview.builder()
                .productId(req.getProductId())
                .customerId(customerId)
                .orderId(req.getOrderId())
                .rating(req.getRating())
                .comment(req.getComment())
                .imageUrls(req.getImageUrls())
                .isVerifiedPurchase(true)
                .isApproved(true)
                .build();

        ProductReview saved = productReviewRepository.save(review);
        updateProductRating(req.getProductId());
        return mapToResponse(saved);
    }

    @Transactional
    public ProductReviewResponse toggleReviewStatus(UUID reviewId) {
        ProductReview review = productReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductReview", reviewId));
        review.setIsApproved(!review.getIsApproved());
        ProductReview saved = productReviewRepository.save(review);
        updateProductRating(review.getProductId());
        return mapToResponse(saved);
    }

    @Transactional
    public void deleteReview(UUID reviewId) {
        ProductReview review = productReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductReview", reviewId));
        productReviewRepository.delete(review);
        updateProductRating(review.getProductId());
    }

    private void updateProductRating(UUID productId) {
        Double avgRating = productReviewRepository.getAverageRatingByProductId(productId);
        Long totalReviews = productReviewRepository.countByProductIdAndIsApprovedTrue(productId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        product.setAverageRating(avgRating != null ? avgRating : 0.0);
        product.setTotalReviews(totalReviews.intValue());
        productRepository.save(product);
    }

    private ProductReviewResponse mapToResponse(ProductReview review) {
        String customerName = customerRepository.findById(review.getCustomerId())
                .map(Customer::getFullName).orElse("Khách hàng");
        return ProductReviewResponse.builder()
                .id(review.getId())
                .productId(review.getProductId())
                .customerId(review.getCustomerId())
                .customerName(customerName)
                .rating(review.getRating())
                .comment(review.getComment())
                .imageUrls(review.getImageUrls())
                .isVerifiedPurchase(review.getIsVerifiedPurchase())
                .isApproved(review.getIsApproved())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
