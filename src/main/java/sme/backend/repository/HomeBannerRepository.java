package sme.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import sme.backend.entity.HomeBanner;

import java.util.List;
import java.util.UUID;

@Repository
public interface HomeBannerRepository extends JpaRepository<HomeBanner, UUID> {

    List<HomeBanner> findByIsActiveTrueAndBannerTypeOrderBySortOrderAsc(HomeBanner.BannerType type);

    @Query("SELECT b FROM HomeBanner b WHERE b.isActive = true AND (b.startDate IS NULL OR b.startDate <= CURRENT_TIMESTAMP) AND (b.endDate IS NULL OR b.endDate >= CURRENT_TIMESTAMP) ORDER BY b.sortOrder ASC")
    List<HomeBanner> findActiveBanners();

    List<HomeBanner> findByBannerTypeOrderBySortOrderAsc(HomeBanner.BannerType type);

    List<HomeBanner> findAllByOrderBySortOrderAsc();
}
