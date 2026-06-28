package sme.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sme.backend.dto.request.CreateArticleRequest;
import sme.backend.dto.request.UpdateArticleRequest;
import sme.backend.dto.response.ArticleResponse;
import sme.backend.entity.Article;
import sme.backend.entity.User;
import sme.backend.exception.BusinessException;
import sme.backend.exception.ResourceNotFoundException;
import sme.backend.repository.ArticleRepository;
import sme.backend.repository.UserRepository;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;

    @Lazy
    @Autowired
    private NotificationService notificationService;

    private String generateSlug(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug;
    }

    @Transactional(readOnly = true)
    public Page<ArticleResponse> searchArticles(String keyword, String type, Boolean isActive, String status, Pageable pageable) {
        Article.ArticleStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = Article.ArticleStatus.valueOf(status); } catch (IllegalArgumentException ignored) {}
        }
        return articleRepository.searchArticles(keyword, type, isActive, statusEnum, pageable)
                .map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public ArticleResponse getArticleById(UUID id) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article", id));
        return mapToResponse(article);
    }

    @Transactional(readOnly = true)
    public ArticleResponse getArticleBySlug(String slug) {
        Article article = articleRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Article with slug", slug));
        return mapToResponse(article);
    }

    @Transactional
    public ArticleResponse createArticle(CreateArticleRequest req, UUID createdByUserId, boolean isAdmin) {
        String slug = req.getSlug() != null && !req.getSlug().isBlank() ? req.getSlug() : generateSlug(req.getTitle());
        if (articleRepository.existsBySlug(slug)) {
            slug = slug + "-" + System.currentTimeMillis();
        }

        Article.ArticleStatus status = isAdmin ? Article.ArticleStatus.PUBLISHED : Article.ArticleStatus.DRAFT;
        boolean active = isAdmin && Boolean.TRUE.equals(req.getIsActive() != null ? req.getIsActive() : true);

        Article article = Article.builder()
                .title(req.getTitle())
                .slug(slug)
                .content(req.getContent())
                .coverImage(req.getCoverImage())
                .authorName(req.getAuthorName())
                .type(req.getType() != null ? req.getType() : "TIN_TUC")
                .isActive(active)
                .status(status)
                .createdByUserId(createdByUserId)
                .build();

        return mapToResponse(articleRepository.save(article));
    }

    @Transactional
    public ArticleResponse submitForApproval(UUID id, UUID submittedBy) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article", id));
        if (article.getStatus() != Article.ArticleStatus.DRAFT
                && article.getStatus() != Article.ArticleStatus.REJECTED) {
            throw new BusinessException("INVALID_STATUS",
                    "Chỉ có thể gửi duyệt bài viết ở trạng thái NHÁP hoặc BỊ TỪ CHỐI");
        }
        if (!submittedBy.equals(article.getCreatedByUserId())) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền gửi duyệt bài viết này");
        }
        article.setStatus(Article.ArticleStatus.PENDING_APPROVAL);
        article.setRejectionReason(null);
        Article saved = articleRepository.save(article);
        notifyAdminsArticlePending(saved, submittedBy);
        return mapToResponse(saved);
    }

    @Transactional
    public ArticleResponse approveArticle(UUID id) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article", id));
        if (article.getStatus() != Article.ArticleStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể duyệt bài viết đang chờ duyệt");
        }
        article.setStatus(Article.ArticleStatus.PUBLISHED);
        article.setIsActive(true);
        article.setRejectionReason(null);
        return mapToResponse(articleRepository.save(article));
    }

    @Transactional
    public ArticleResponse rejectArticle(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MISSING_REASON", "Vui lòng nhập lý do từ chối");
        }
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article", id));
        if (article.getStatus() != Article.ArticleStatus.PENDING_APPROVAL) {
            throw new BusinessException("INVALID_STATUS", "Chỉ có thể từ chối bài viết đang chờ duyệt");
        }
        article.setStatus(Article.ArticleStatus.REJECTED);
        article.setIsActive(false);
        article.setRejectionReason(reason);
        return mapToResponse(articleRepository.save(article));
    }

    @Transactional
    public ArticleResponse updateArticle(UUID id, UpdateArticleRequest req) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article", id));

        if (req.getTitle() != null) article.setTitle(req.getTitle());
        if (req.getSlug() != null && !req.getSlug().isBlank() && !req.getSlug().equals(article.getSlug())) {
            if (articleRepository.existsBySlug(req.getSlug())) {
                throw new BusinessException("DUPLICATE_SLUG", "Slug đã tồn tại");
            }
            article.setSlug(req.getSlug());
        }
        if (req.getContent() != null) article.setContent(req.getContent());
        if (req.getCoverImage() != null) article.setCoverImage(req.getCoverImage());
        if (req.getAuthorName() != null) article.setAuthorName(req.getAuthorName());
        if (req.getType() != null) article.setType(req.getType());
        if (req.getIsActive() != null) article.setIsActive(req.getIsActive());

        return mapToResponse(articleRepository.save(article));
    }

    @Transactional
    public void deleteArticle(UUID id) {
        if (!articleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Article", id);
        }
        articleRepository.deleteById(id);
    }

    private void notifyAdminsArticlePending(Article article, UUID authorId) {
        try {
            List<User> admins = userRepository.findByRoleAndIsActiveTrue(User.UserRole.ROLE_ADMIN);
            String authorName = userRepository.findById(authorId)
                    .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                    .orElse("Manager");
            for (User admin : admins) {
                sme.backend.entity.Notification notif = sme.backend.entity.Notification.builder()
                        .type("ARTICLE_PENDING_APPROVAL")
                        .title("Bài viết chờ duyệt")
                        .message("\"" + article.getTitle() + "\" — " + authorName + " gửi duyệt")
                        .payload(Map.of(
                                "articleId", article.getId().toString(),
                                "articleTitle", article.getTitle()))
                        .isRead(false)
                        .userId(admin.getId())
                        .build();
                notificationService.saveNotification(notif);
            }
        } catch (Exception e) {
            // notification failure must not block the submit
        }
    }

    private ArticleResponse mapToResponse(Article a) {
        return ArticleResponse.builder()
                .id(a.getId())
                .title(a.getTitle())
                .slug(a.getSlug())
                .content(a.getContent())
                .coverImage(a.getCoverImage())
                .authorName(a.getAuthorName())
                .type(a.getType())
                .isActive(a.getIsActive())
                .status(a.getStatus() != null ? a.getStatus().name() : "DRAFT")
                .rejectionReason(a.getRejectionReason())
                .createdByUserId(a.getCreatedByUserId())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .createdBy(a.getCreatedBy())
                .updatedBy(a.getUpdatedBy())
                .build();
    }
}
