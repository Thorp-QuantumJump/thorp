package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.ReleaseLogRepository;
import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ReleaseLogRepositoryImpl implements ReleaseLogRepository {

    private final LearningReleaseLogJpaRepository logJpaRepository;
    private final LearningStageJpaRepository stageJpaRepository;
    private final LearningArticleJpaRepository articleJpaRepository;

    public ReleaseLogRepositoryImpl(
            LearningReleaseLogJpaRepository logJpaRepository,
            LearningStageJpaRepository stageJpaRepository,
            LearningArticleJpaRepository articleJpaRepository) {
        this.logJpaRepository = logJpaRepository;
        this.stageJpaRepository = stageJpaRepository;
        this.articleJpaRepository = articleJpaRepository;
    }

    /** 바로 flush한다. 같은 {@code entryKey}의 제약 위반이 트랜잭션 끝이 아니라 이 자리에서 드러나게 한다. */
    @Override
    public void append(ReleaseLogEntry entry) {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        logJpaRepository.saveAndFlush(
                new LearningReleaseLogJpaEntity(
                        entry.entryKey(),
                        entry.kind(),
                        stages.idOf(entry.stageKey()),
                        entry.articleId() == null
                                ? null
                                : PersistenceLookups.requireArticleId(
                                        articleJpaRepository, entry.articleId()),
                        entry.newCount(),
                        entry.rewrittenCount(),
                        entry.rewrittenTitles(),
                        entry.articleTitle(),
                        entry.actorUserId(),
                        entry.occurredAt()));
    }

    /** 저장된 줄이 도메인 규칙을 어기면 저장 데이터의 문제라 {@link IllegalStateException}으로 실패한다. */
    @Override
    public List<ReleaseLogEntry> findAllLatestFirst() {
        StageLookup stages = StageLookup.load(stageJpaRepository);
        return logJpaRepository.findAllByOrderByOccurredAtDescIdDesc().stream()
                .map(row -> toDomain(row, stages))
                .toList();
    }

    private ReleaseLogEntry toDomain(LearningReleaseLogJpaEntity row, StageLookup stages) {
        return LearningContentJson.storedValue(
                "learning_release_log(" + row.getEntryKey() + ")",
                () ->
                        new ReleaseLogEntry(
                                row.getEntryKey(),
                                row.getKind(),
                                stages.keyOf(row.getStageId()),
                                row.getArticleId() == null ? null : articleKey(row.getArticleId()),
                                row.getNewCount(),
                                row.getRewrittenCount(),
                                LearningContentJson.required(
                                        row.getRewrittenTitles(),
                                        "learning_release_log.rewritten_titles"),
                                row.getArticleTitle(),
                                row.getActorUserId(),
                                row.getOccurredAt()));
    }

    private ArticleId articleKey(Long id) {
        return articleJpaRepository
                .findArticleKeyById(id)
                .map(ArticleId::new)
                .orElseThrow(
                        () -> new IllegalStateException("learning article not found: id=" + id));
    }
}
