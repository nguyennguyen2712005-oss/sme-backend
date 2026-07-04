package sme.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "customer_addresses")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CustomerAddress extends BaseEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "receiver_name", nullable = false, length = 150)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "province_city", nullable = false, length = 100)
    private String provinceCity;

    @Column(length = 100)
    private String district;

    @Column(length = 100)
    private String ward;

    @Column(name = "specific_address", nullable = false, columnDefinition = "TEXT")
    private String specificAddress;

    @Column(name = "is_default")
    @Builder.Default
    private Boolean isDefault = false;

    @Column
    private Double latitude;

    @Column
    private Double longitude;
}
