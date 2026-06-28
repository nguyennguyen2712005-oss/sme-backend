package sme.backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import sme.backend.entity.Article;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ArticleRepository extends JpaRepository<Article, UUID> {

    Optional<Article> findBySlugAndIsActiveTrue(String slug);

    boolean existsBySlug(String slug);

    @Query("""
        SELECT a FROM Article a
        WHERE (:type IS NULL OR a.type = :type)
        AND (:isActive IS NULL OR a.isActive = :isActive)
        AND (:status IS NULL OR a.status = :status)
        AND (:keyword IS NULL OR :keyword = ''
          OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
          OR LOWER(a.content) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY a.createdAt DESC
        """)
    Page<Article> searchArticles(@Param("keyword") String keyword,
                                 @Param("type") String type,
                                 @Param("isActive") Boolean isActive,
                                 @Param("status") Article.ArticleStatus status,
                                 Pageable pageable);
}
