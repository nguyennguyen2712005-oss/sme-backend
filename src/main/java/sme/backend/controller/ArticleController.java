package sme.backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sme.backend.dto.request.CreateArticleRequest;
import sme.backend.dto.request.UpdateArticleRequest;
import sme.backend.dto.response.ApiResponse;
import sme.backend.dto.response.ArticleResponse;
import sme.backend.dto.response.PageResponse;
import sme.backend.service.ArticleService;

import java.util.UUID;

@RestController
@RequestMapping("/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    // PUBLIC API cho Customer
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ArticleResponse>>> searchArticles(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                PageResponse.of(articleService.searchArticles(keyword, type, isActive,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))))));
    }

    // Lấy chi tiết bài viết qua Slug (Dùng cho trang chi tiết Bài viết bên Khách hàng)
    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<ArticleResponse>> getArticleBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(articleService.getArticleBySlug(slug)));
    }

    // Lấy chi tiết bài viết qua ID
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ArticleResponse>> getArticleById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(articleService.getArticleById(id)));
    }

    // ADMIN API
    @PostMapping
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<ArticleResponse>> createArticle(@Valid @RequestBody CreateArticleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(articleService.createArticle(req)));
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
}
