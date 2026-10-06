package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.PublishedArticleView;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class LearningArticleRepositoryImpl implements LearningArticleRepository {

    private final LearningArticleJpaRepository articleJpaRepository;
    private final LearningTermJpaRepository termJpaRepository;
    private final LearningArticleRevisionJpaRepository revisionJpaRepository;
    private final LearningStageJpaRepository stageJpaRepository;
    private final EntityManager entityManager;

    public LearningArticleRepositoryImpl(
            LearningArticleJpaRepository articleJpaRepository,
            LearningTermJpaRepository termJpaRepository,
            LearningArticleRevisionJpaRepository revisionJpaRepository,
            LearningStageJpaRepository stageJpaRepository,
            EntityManager entityManager) {
        this.articleJpaRepository = articleJpaRepository;
        this.termJpaRepository = termJpaRepository;
        this.revisionJpaRepository = revisionJpaRepository;
        this.stageJpaRepository = stageJpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<LearningArticle> findByArticleKey(ArticleId articleId) {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        return articleJpaRepository
                .findByArticleKey(articleId.value())
                .map(entity -> withTerms(entity, stages));
    }

    /** 잠근 뒤 {@code refresh}한다. 이 트랜잭션이 행을 먼저 읽었다면 잠금 쿼리의 결과가 1차 캐시에 밀려 버려진다. */
    @Override
    public Optional<LearningArticle> findByArticleKeyForUpdate(ArticleId articleId) {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        return articleJpaRepository
                .lockByArticleKey(articleId.value())
                .map(
                        entity -> {
                            entityManager.refresh(entity);
                            return withTerms(entity, stages);
                        });
    }

    @Override
    public List<LearningArticle> findAllOrdered() {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        return articleJpaRepository.findAllInStageOrder().stream()
                .map(entity -> withTerms(entity, stages))
                .toList();
    }

    @Override
    public List<LearningArticleSummaryResult> findPublishedOrdered() {
        return revisionJpaRepository.findPublishedSummaries().stream()
                .map(
                        row ->
                                new LearningArticleSummaryResult(
                                        LearningContentJson.required(
                                                row.getArticleId(), "articleId"),
                                        LearningContentJson.required(row.getTitle(), "title"),
                                        LearningContentJson.required(row.getQuestion(), "question"),
                                        LearningContentJson.required(
                                                row.getReadingOrder(), "readingOrder")))
                .toList();
    }

    @Override
    public Optional<ArticleRevision> findPublishedRevision(ArticleId articleId) {
        return revisionJpaRepository
                .findCurrentlyPublished(articleId.value())
                .map(ArticleRevisionMapper::toDomain);
    }

    /** 판과 공개 배치를 같은 글 행에서 읽는다. 공개 배치가 비어 있으면 테이블 CHECK가 막는 상태라 실패시킨다. */
    @Override
    public Optional<PublishedArticleView> findPublished(ArticleId articleId) {
        return revisionJpaRepository
                .findCurrentlyPublished(articleId.value())
                .map(
                        revision -> {
                            LearningArticleJpaEntity article =
                                    articleJpaRepository
                                            .findByArticleKey(articleId.value())
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "published article vanished: "
                                                                            + articleId));
                            StageLookup stages = StageLookup.load(stageJpaRepository);
                            return new PublishedArticleView(
                                    ArticleRevisionMapper.toDomain(revision),
                                    new Placement(
                                            stages.keyOf(
                                                    LearningContentJson.required(
                                                            article.getPublishedStageId(),
                                                            "published_stage_id")),
                                            LearningContentJson.required(
                                                    article.getPublishedStagePosition(),
                                                    "published_stage_position")));
                        });
    }

    @Override
    public List<CurrentArticleEntry> findCurrentInReadingOrder() {
        return revisionJpaRepository.findCurrentInReadingOrder().stream()
                .map(
                        row ->
                                new CurrentArticleEntry(
                                        row.getArticleId(),
                                        LearningContentJson.required(row.getTitle(), "title"),
                                        row.getStageKey(),
                                        row.getStageNumber(),
                                        row.getPosition()))
                .toList();
    }

    @Override
    public LearningArticle save(LearningArticle article) {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        LearningArticleJpaEntity entity;
        if (article.id() == null) {
            entity = articleJpaRepository.save(LearningArticleMapper.toEntity(article, stages));
        } else {
            entity =
                    articleJpaRepository
                            .findById(article.id())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "article not found: id=" + article.id()));
            LearningArticleMapper.apply(entity, article, stages);
            entity = articleJpaRepository.saveAndFlush(entity);
        }
        return withTerms(entity, stages);
    }

    @Override
    public boolean existsByArticleKey(ArticleId articleId) {
        return articleJpaRepository.existsByArticleKey(articleId.value());
    }

    private LearningArticle withTerms(LearningArticleJpaEntity entity, StageLookup stages) {
        return LearningArticleMapper.toDomain(
                entity, termJpaRepository.findByArticleIdOrderByIdAsc(entity.getId()), stages);
    }
}
