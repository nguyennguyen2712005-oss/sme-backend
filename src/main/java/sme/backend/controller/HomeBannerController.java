package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreateBannerRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.entity.HomeBanner;
import sme.backend.service.HomeBannerService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/banners")
@RequiredArgsConstructor
public class HomeBannerController {

    private final HomeBannerService homeBannerService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<HomeBanner>>> getActiveBanners(
            @RequestParam(required = false) String bannerType) {
        return ResponseEntity.ok(ApiResponse.ok(homeBannerService.getActiveBanners(bannerType)));
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<HomeBanner>>> getAllBanners(
            @RequestParam(required = false) String bannerType) {
        return ResponseEntity.ok(ApiResponse.ok(homeBannerService.getAllBanners(bannerType)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<HomeBanner>> create(@Valid @RequestBody CreateBannerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(homeBannerService.createBanner(req)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<HomeBanner>> update(@PathVariable UUID id,
            @Valid @RequestBody CreateBannerRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(homeBannerService.updateBanner(id, req)));
    }

    @PatchMapping("/{id}/toggle-active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<HomeBanner>> toggleActive(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(homeBannerService.toggleActive(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        homeBannerService.deleteBanner(id);
        return ResponseEntity.ok(ApiResponse.ok("Xóa banner thành công", null));
    }

    @PutMapping("/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> reorder(@RequestBody List<UUID> orderedIds) {
        homeBannerService.reorderBanners(orderedIds);
        return ResponseEntity.ok(ApiResponse.ok("Sắp xếp thành công", null));
    }
}
