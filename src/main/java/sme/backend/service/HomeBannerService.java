package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreateBannerRequest;
import sme.backend.entity.HomeBanner;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.HomeBannerRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HomeBannerService {

    private final HomeBannerRepository homeBannerRepository;

    @Transactional(readOnly = true)
    public List<HomeBanner> getActiveBanners(String bannerType) {
        if (bannerType != null && !bannerType.isBlank()) {
            return homeBannerRepository
                    .findByIsActiveTrueAndBannerTypeOrderBySortOrderAsc(HomeBanner.BannerType.valueOf(bannerType));
        }
        return homeBannerRepository.findActiveBanners();
    }

    @Transactional(readOnly = true)
    public List<HomeBanner> getAllBanners(String bannerType) {
        if (bannerType != null && !bannerType.isBlank()) {
            return homeBannerRepository
                    .findByBannerTypeOrderBySortOrderAsc(HomeBanner.BannerType.valueOf(bannerType));
        }
        return homeBannerRepository.findAllByOrderBySortOrderAsc();
    }

    @Transactional
    public HomeBanner createBanner(CreateBannerRequest req) {
        HomeBanner banner = HomeBanner.builder()
                .title(req.getTitle())
                .imageUrl(req.getImageUrl())
                .linkUrl(req.getLinkUrl())
                .buttonText(req.getButtonText())
                .sortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0)
                .bannerType(req.getBannerType())
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .build();
        return homeBannerRepository.save(banner);
    }

    @Transactional
    public HomeBanner updateBanner(UUID id, CreateBannerRequest req) {
        HomeBanner banner = homeBannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("HomeBanner", id));

        if (req.getTitle() != null)
            banner.setTitle(req.getTitle());
        if (req.getImageUrl() != null)
            banner.setImageUrl(req.getImageUrl());
        if (req.getLinkUrl() != null)
            banner.setLinkUrl(req.getLinkUrl());
        if (req.getButtonText() != null)
            banner.setButtonText(req.getButtonText());
        if (req.getSortOrder() != null)
            banner.setSortOrder(req.getSortOrder());
        if (req.getBannerType() != null)
            banner.setBannerType(req.getBannerType());
        if (req.getIsActive() != null)
            banner.setIsActive(req.getIsActive());
        if (req.getStartDate() != null)
            banner.setStartDate(req.getStartDate());
        if (req.getEndDate() != null)
            banner.setEndDate(req.getEndDate());

        return homeBannerRepository.save(banner);
    }

    @Transactional
    public HomeBanner toggleActive(UUID id) {
        HomeBanner banner = homeBannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("HomeBanner", id));
        banner.setIsActive(!banner.getIsActive());
        return homeBannerRepository.save(banner);
    }

    @Transactional
    public void deleteBanner(UUID id) {
        HomeBanner banner = homeBannerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("HomeBanner", id));
        homeBannerRepository.delete(banner);
    }

    @Transactional
    public void reorderBanners(List<UUID> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            UUID id = orderedIds.get(i);
            homeBannerRepository.findById(id).ifPresent(banner -> {
                banner.setSortOrder(orderedIds.indexOf(id));
                homeBannerRepository.save(banner);
            });
        }
    }
}
