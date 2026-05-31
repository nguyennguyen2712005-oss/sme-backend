package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import java.util.UUID;

@Entity
@Table(name = "customers")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Customer extends BaseEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_channel", length = 50)
    private AcquisitionChannel acquisitionChannel;

    @Column(name = "phone_number", unique = true, nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(length = 255)
    private String email;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "province_code", length = 50)
    private String provinceCode;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(length = 10)
    private String gender;  // MALE, FEMALE, OTHER

    @Column(name = "loyalty_points")
    @Builder.Default
    private Integer loyaltyPoints = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_tier", length = 50)
    @Builder.Default
    private CustomerTier customerTier = CustomerTier.STANDARD;

    @Column(name = "total_spent", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    public enum CustomerTier {
        STANDARD, SILVER, GOLD
    }

    public enum AcquisitionChannel {
        ONLINE, POS
    }

    public void addPoints(int points) {
        this.loyaltyPoints += points;
        updateTier();
    }

    public void deductPoints(int points) {
        if (this.loyaltyPoints < points) {
            throw new IllegalStateException("Điểm tích lũy không đủ.");
        }
        this.loyaltyPoints -= points;
    }

    private void updateTier() {

        BigDecimal silverThreshold = new BigDecimal("500000"); 
        BigDecimal goldThreshold = new BigDecimal("2000000");

        if (this.totalSpent != null && this.totalSpent.compareTo(goldThreshold) >= 0) {
            this.customerTier = CustomerTier.GOLD;
        } else if (this.totalSpent != null && this.totalSpent.compareTo(silverThreshold) >= 0) {
            this.customerTier = CustomerTier.SILVER;
        }
    }
}
