package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class LearningArticleRepositoryImpl implements LearningArticleRepository {

    /** 판 내용의 읽는 순서, 같으면 글 식별자 순(learning-public-api §1). */
    private static final Comparator<ArticleRevision> PUBLISHED_ORDER =
            Comparator.comparingInt(
                            (ArticleRevision revision) -> revision.snapshot().readingOrder())
                    .thenComparing(revision -> revision.snapshot().articleId().value());

    private final LearningArticleJpaRepository articleJpaRepository;
    private final LearningTermJpaRepository termJpaRepository;
    private final LearningArticleRevisionJpaRepository revisionJpaRepository;
    private final EntityManager entityManager;

    public LearningArticleRepositoryImpl(
            LearningArticleJpaRepository articleJpaRepository,
            LearningTermJpaRepository termJpaRepository,
            LearningArticleRevisionJpaRepository revisionJpaRepository,
            EntityManager entityManager) {
        this.articleJpaRepository = articleJpaRepository;
        this.termJpaRepository = termJpaRepository;
        this.revisionJpaRepository = revisionJpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<LearningArticle> findByArticleKey(ArticleId articleId) {
        return articleJpaRepository.findByArticleKey(articleId.value()).map(this::withTerms);
    }

    /** 잠근 뒤 {@code refresh}한다. 이 트랜잭션이 행을 먼저 읽었다면 잠금 쿼리의 결과가 1차 캐시에 밀려 버려진다. */
    @Override
    public Optional<LearningArticle> findByArticleKeyForUpdate(ArticleId articleId) {
        return articleJpaRepository
                .lockByArticleKey(articleId.value())
                .map(
                        entity -> {
                            entityManager.refresh(entity);
                            return withTerms(entity);
                        });
    }

    @Override
    public List<LearningArticle> findAllOrdered() {
        return articleJpaRepository.findAllByOrderByReadingOrderAscArticleKeyAsc().stream()
                .map(this::withTerms)
                .toList();
    }

    /** 공개 중인 판을 읽어 판 내용의 순서로 정렬한다. 순서 값이 JSONB 안에 있어 DB 정렬 대신 여기서 정렬한다. 글은 수십 편 규모다. */
    @Override
    public List<ArticleRevision> findPublishedOrdered() {
        return revisionJpaRepository.findCurrentlyPublished().stream()
                .map(ArticleRevisionMapper::toDomain)
                .sorted(PUBLISHED_ORDER)
                .toList();
    }

    @Override
    public Optional<ArticleRevision> findPublishedRevision(ArticleId articleId) {
        return revisionJpaRepository
                .findCurrentlyPublished(articleId.value())
                .map(ArticleRevisionMapper::toDomain);
    }

    @Override
    public LearningArticle save(LearningArticle article) {
        LearningArticleJpaEntity entity;
        if (article.id() == null) {
            entity = articleJpaRepository.save(LearningArticleMapper.toEntity(article));
        } else {
            entity =
                    articleJpaRepository
                            .findById(article.id())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "article not found: id=" + article.id()));
            LearningArticleMapper.apply(entity, article);
            entity = articleJpaRepository.saveAndFlush(entity);
        }
        return withTerms(entity);
    }

    @Override
    public boolean existsByArticleKey(ArticleId articleId) {
        return articleJpaRepository.existsByArticleKey(articleId.value());
    }

    private LearningArticle withTerms(LearningArticleJpaEntity entity) {
        return LearningArticleMapper.toDomain(
                entity, termJpaRepository.findByArticleIdOrderByIdAsc(entity.getId()));
    }
}
