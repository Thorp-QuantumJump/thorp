package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.LearningArticleSummaryResult;
import com.ssafy.thorp.learning.application.result.PublishedArticleEntry;
import com.ssafy.thorp.learning.application.result.PublishedArticleView;
import com.ssafy.thorp.learning.application.result.StageArticleEntry;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleRevision;
import com.ssafy.thorp.learning.domain.model.ArticleStatus;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningArticleJpaRepository.StageArticleRow;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningArticleRevisionJpaRepository.PublishedEntryRow;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class LearningArticleRepositoryImpl implements LearningArticleRepository {

    private final LearningArticleJpaRepository articleJpaRepository;
    private final LearningTermJpaRepository termJpaRepository;
    private final LearningArticleRevisionJpaRepository revisionJpaRepository;
    private final StageDirectory stageDirectory;
    private final EntityManager entityManager;

    public LearningArticleRepositoryImpl(
            LearningArticleJpaRepository articleJpaRepository,
            LearningTermJpaRepository termJpaRepository,
            LearningArticleRevisionJpaRepository revisionJpaRepository,
            StageDirectory stageDirectory,
            EntityManager entityManager) {
        this.articleJpaRepository = articleJpaRepository;
        this.termJpaRepository = termJpaRepository;
        this.revisionJpaRepository = revisionJpaRepository;
        this.stageDirectory = stageDirectory;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<LearningArticle> findByArticleKey(ArticleId articleId) {
        return articleJpaRepository.findByArticleKey(articleId.value()).map(this::withTerms);
    }

    /**
     * 잠근 뒤 글 행과 용어 행을 {@code refresh}한다. 이 트랜잭션이 행을 먼저 읽었다면 잠금 쿼리의 결과가 1차 캐시에 밀려 버려진다. 용어 행도 같다.
     * 넘기기를 마친 다른 트랜잭션은 초안 번호를 올리지 않고 용어 행의 {@code transfer_from_article_id}만 비우므로, 잠그기 전에 읽은 용어 행을
     * 그대로 쓰면 끝난 넘겨받기를 다시 판정한다.
     */
    @Override
    public Optional<LearningArticle> findByArticleKeyForUpdate(ArticleId articleId) {
        return articleJpaRepository
                .lockByArticleKey(articleId.value())
                .map(
                        entity -> {
                            entityManager.refresh(entity);
                            List<LearningTermJpaEntity> terms =
                                    termJpaRepository.findByArticleIdOrderByIdAsc(entity.getId());
                            terms.forEach(entityManager::refresh);
                            return LearningArticleMapper.toDomain(
                                    entity,
                                    terms,
                                    stageDirectory.lookup(),
                                    id ->
                                            PersistenceLookups.requireArticleKey(
                                                    articleJpaRepository, id));
                        });
    }

    /** 용어는 모든 글 것을 한 번에 읽어 글마다 나눈다. 글마다 따로 읽지 않는다. */
    @Override
    public List<LearningArticle> findAllOrdered() {
        return toDomain(
                articleJpaRepository.findAllInStageOrder(),
                termJpaRepository.findAllByOrderByIdAsc());
    }

    /** 글과 그 글들의 용어를 두 문장으로 읽는다. */
    @Override
    public List<LearningArticle> findByArticleKeys(Collection<ArticleId> articleIds) {
        if (articleIds.isEmpty()) {
            return List.of();
        }
        List<LearningArticleJpaEntity> entities =
                articleJpaRepository.findByArticleKeyIn(
                        articleIds.stream().map(ArticleId::value).toList());
        if (entities.isEmpty()) {
            return List.of();
        }
        return toDomain(
                entities,
                termJpaRepository.findByArticleIdInOrderByIdAsc(
                        entities.stream().map(LearningArticleJpaEntity::getId).toList()));
    }

    @Override
    public List<StageArticleEntry> findStageEntries() {
        return articleJpaRepository.findStageRows().stream()
                .map(LearningArticleRepositoryImpl::toStageEntry)
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
        return byArticle(revisionJpaRepository.findAllCurrentlyPublished());
    }

    @Override
    public Map<ArticleId, ArticleRevision> findPublishedRevisions(
            Collection<ArticleId> articleIds) {
        if (articleIds.isEmpty()) {
            return Map.of();
        }
        return byArticle(
                revisionJpaRepository.findCurrentlyPublishedIn(
                        articleIds.stream().map(ArticleId::value).toList()));
    }

    private static Map<ArticleId, ArticleRevision> byArticle(List<PublishedRevisionRow> rows) {
        Map<ArticleId, ArticleRevision> revisions = new HashMap<>();
        for (PublishedRevisionRow row : rows) {
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
                .map(this::toView);
    }

    /** 저장된 값이 비어 있으면 저장 데이터의 문제라 실패시킨다. 판 JSON의 다른 키는 읽지 않는다. */
    @Override
    public List<PublishedArticleEntry> findPublishedEntries() {
        return revisionJpaRepository.findPublishedEntries().stream()
                .map(LearningArticleRepositoryImpl::toPublishedEntry)
                .toList();
    }

    private PublishedArticleView toView(PublishedRevisionRow row) {
        return new PublishedArticleView(
                ArticleRevisionMapper.toDomain(row.revision()),
                new Placement(
                        stageDirectory
                                .lookup()
                                .keyOf(
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

    @Override
    public Map<ArticleId, List<QuestionId>> findCurrentQuestionIds() {
        Map<ArticleId, List<QuestionId>> questions = new LinkedHashMap<>();
        for (LearningArticleRevisionJpaRepository.CurrentQuestionRow row :
                revisionJpaRepository.findCurrentQuestionIds()) {
            questions
                    .computeIfAbsent(new ArticleId(row.getArticleId()), key -> new ArrayList<>())
                    .add(
                            new QuestionId(
                                    LearningContentJson.required(
                                            row.getQuestionId(), "quiz.questionId")));
        }
        return questions;
    }

    @Override
    public Optional<Edition> findPublishedEdition(ArticleId articleId) {
        return revisionJpaRepository.findPublishedEdition(articleId.value());
    }

    /**
     * 돌려주는 값은 넘겨받은 글에 대리키를 채운 것이다. 저장된 용어 행으로 글을 다시 만들면, 형식을 바꾸는 저장에서 용어를 아직 바꾸기 전에 옛 용어가 새 형식에 맞지
     * 않아 실패한다.
     */
    @Override
    public LearningArticle save(LearningArticle article) {
        StageLookup stages = stageDirectory.lookup();
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
    private LearningArticle withTerms(LearningArticleJpaEntity entity) {
        return LearningArticleMapper.toDomain(
                entity,
                termJpaRepository.findByArticleIdOrderByIdAsc(entity.getId()),
                stageDirectory.lookup(),
                id -> PersistenceLookups.requireArticleKey(articleJpaRepository, id));
    }

    /**
     * 글마다 용어를 나눠 붙인다. 넘겨받는 중인 용어의 소유 글은 함께 읽은 글이면 그 키를 쓰고, 아니면 따로 읽는다.
     *
     * @param terms 그 글들의 용어 행, {@code id} 순서
     */
    private List<LearningArticle> toDomain(
            List<LearningArticleJpaEntity> entities, List<LearningTermJpaEntity> terms) {
        StageLookup stages = stageDirectory.lookup();
        Map<Long, List<LearningTermJpaEntity>> termsByArticle = new HashMap<>();
        for (LearningTermJpaEntity term : terms) {
            termsByArticle.computeIfAbsent(term.getArticleId(), key -> new ArrayList<>()).add(term);
        }
        Map<Long, ArticleId> keys = new HashMap<>();
        entities.forEach(entity -> keys.put(entity.getId(), new ArticleId(entity.getArticleKey())));
        return entities.stream()
                .map(
                        entity ->
                                LearningArticleMapper.toDomain(
                                        entity,
                                        termsByArticle.getOrDefault(entity.getId(), List.of()),
                                        stages,
                                        id ->
                                                Optional.ofNullable(keys.get(id))
                                                        .orElseGet(
                                                                () ->
                                                                        PersistenceLookups
                                                                                .requireArticleKey(
                                                                                        articleJpaRepository,
                                                                                        id))))
                .toList();
    }

    private static StageArticleEntry toStageEntry(StageArticleRow row) {
        return new StageArticleEntry(
                new ArticleId(row.getArticleId()),
                row.getTitle(),
                ArticleFormat.valueOf(row.getFormat()),
                ArticleStatus.valueOf(row.getStatus()),
                row.getPublishedEdition() == null
                        ? null
                        : Edition.valueOf(row.getPublishedEdition()),
                row.getDraftVersion(),
                new Placement(new StageKey(row.getStageKey()), row.getPosition()),
                row.getPublishedStageKey() == null
                        ? null
                        : new Placement(
                                new StageKey(row.getPublishedStageKey()),
                                LearningContentJson.required(
                                        row.getPublishedPosition(), "published_stage_position")));
    }

    private static PublishedArticleEntry toPublishedEntry(PublishedEntryRow row) {
        return LearningContentJson.storedValue(
                "learning_article_revision(" + row.getArticleId() + ")",
                () -> {
                    Edition edition = Edition.valueOf(row.getEdition());
                    return new PublishedArticleEntry(
                            new ArticleId(row.getArticleId()),
                            edition,
                            new StageKey(row.getStageKey()),
                            row.getPosition(),
                            LearningContentJson.required(row.getTitle(), "title"),
                            LearningContentJson.required(row.getQuestion(), "question"),
                            edition == Edition.CURRENT
                                    ? LearningContentJson.required(
                                            row.getReadingMinutes(), "reading.readingMinutes")
                                    : null,
                            row.getHasExample());
                });
    }
}
