package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreateArticleRequest;
import sme.backend.dto.request.UpdateArticleRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.ArticleResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.security.UserPrincipal;
import sme.backend.service.ArticleService;
import sme.backend.entity.User;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    /** PUBLIC — only PUBLISHED articles (isActive=true) */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ArticleResponse>>> searchArticles(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(articleService.searchArticles(keyword, type, isActive, status,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))))));
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<ArticleResponse>> getArticleBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(articleService.getArticleBySlug(slug)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ArticleResponse>> getArticleById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(articleService.getArticleById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ArticleResponse>> createArticle(
            @Valid @RequestBody CreateArticleRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        boolean isAdmin = principal.getRole() == User.UserRole.ROLE_ADMIN;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(articleService.createArticle(req, principal.getId(), isAdmin)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ArticleResponse>> updateArticle(
            @PathVariable UUID id, @RequestBody UpdateArticleRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(articleService.updateArticle(id, req)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteArticle(@PathVariable UUID id) {
        articleService.deleteArticle(id);
        return ResponseEntity.ok(ApiResponse.ok("Xóa bài viết thành công", null));
    }

    /** Manager gửi duyệt bài của mình */
    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<ApiResponse<ArticleResponse>> submitArticle(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Đã gửi duyệt bài viết",
                articleService.submitForApproval(id, principal.getId())));
    }

    /** Admin duyệt bài */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ArticleResponse>> approveArticle(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok("Duyệt bài viết thành công",
                articleService.approveArticle(id)));
    }

    /** Admin từ chối bài */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ArticleResponse>> rejectArticle(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok("Đã từ chối bài viết",
                articleService.rejectArticle(id, body != null ? body.getOrDefault("reason", "") : "")));
    }
}
