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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 용어는 모든 글 것을 한 번에 읽어 글마다 나눈다. 글마다 따로 읽지 않는다. */
    @Override
    public List<LearningArticle> findAllOrdered() {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        Map<Long, List<LearningTermJpaEntity>> termsByArticle = new HashMap<>();
        for (LearningTermJpaEntity term : termJpaRepository.findAllByOrderByIdAsc()) {
            termsByArticle.computeIfAbsent(term.getArticleId(), key -> new ArrayList<>()).add(term);
        }
        List<LearningArticleJpaEntity> entities = articleJpaRepository.findAllInStageOrder();
        Map<Long, ArticleId> keys = new HashMap<>();
        entities.forEach(entity -> keys.put(entity.getId(), new ArticleId(entity.getArticleKey())));
        return entities.stream()
                .map(
                        entity ->
                                LearningArticleMapper.toDomain(
                                        entity,
                                        termsByArticle.getOrDefault(entity.getId(), List.of()),
                                        stages,
                                        id -> requireKey(keys.get(id), id)))
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

    @Override
    public Map<ArticleId, ArticleRevision> findPublishedRevisions() {
        Map<ArticleId, ArticleRevision> revisions = new HashMap<>();
        for (PublishedRevisionRow row : revisionJpaRepository.findAllCurrentlyPublished()) {
            revisions.put(
                    new ArticleId(row.articleKey()),
                    ArticleRevisionMapper.toDomain(row.revision()));
        }
        return revisions;
    }

    /** 판과 공개 배치를 한 문장으로 읽는다. 공개 배치가 비어 있으면 테이블 CHECK가 막는 상태라 실패시킨다. */
    @Override
    public Optional<PublishedArticleView> findPublished(ArticleId articleId) {
        return revisionJpaRepository
                .findCurrentlyPublishedWithPlacement(articleId.value())
                .map(row -> toView(row, StageLookup.load(stageJpaRepository)));
    }

    @Override
    public List<PublishedArticleView> findAllPublished() {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        return revisionJpaRepository.findAllCurrentlyPublished().stream()
                .map(row -> toView(row, stages))
                .toList();
    }

    private static PublishedArticleView toView(PublishedRevisionRow row, StageLookup stages) {
        return new PublishedArticleView(
                ArticleRevisionMapper.toDomain(row.revision()),
                new Placement(
                        stages.keyOf(
                                LearningContentJson.required(
                                        row.publishedStageId(), "published_stage_id")),
                        LearningContentJson.required(
                                row.publishedStagePosition(), "published_stage_position")));
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

    /**
     * 돌려주는 값은 넘겨받은 글에 대리키를 채운 것이다. 저장된 용어 행으로 글을 다시 만들면, 형식을 바꾸는 저장에서 용어를 아직 바꾸기 전에 옛 용어가 새 형식에 맞지
     * 않아 실패한다.
     */
    @Override
    public LearningArticle save(LearningArticle article) {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        if (article.id() == null) {
            LearningArticleJpaEntity created =
                    articleJpaRepository.save(LearningArticleMapper.toEntity(article, stages));
            return article.withId(created.getId());
        }
        LearningArticleJpaEntity entity =
                articleJpaRepository
                        .findById(article.id())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "article not found: id=" + article.id()));
        LearningArticleMapper.apply(entity, article, stages);
        articleJpaRepository.saveAndFlush(entity);
        return article;
    }

    @Override
    public boolean existsByArticleKey(ArticleId articleId) {
        return articleJpaRepository.existsByArticleKey(articleId.value());
    }

    /** 넘겨받는 중인 용어가 있을 때만 소유 글의 {@code articleId}를 읽는다. */
    private LearningArticle withTerms(LearningArticleJpaEntity entity, StageLookup stages) {
        return LearningArticleMapper.toDomain(
                entity,
                termJpaRepository.findByArticleIdOrderByIdAsc(entity.getId()),
                stages,
                id ->
                        requireKey(
                                articleJpaRepository
                                        .findArticleKeyById(id)
                                        .map(ArticleId::new)
                                        .orElse(null),
                                id));
    }

    /** 외래키가 가리키는 글이 없으면 저장 데이터가 깨진 것이다. */
    private static ArticleId requireKey(ArticleId key, Long id) {
        if (key == null) {
            throw new IllegalStateException("learning article not found: id=" + id);
        }
        return key;
    }
}
