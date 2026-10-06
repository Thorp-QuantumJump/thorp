package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.PublicationEventRepository;
import com.ssafy.thorp.learning.domain.model.PublicationEvent;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 게시 기록은 추가·조회만 한다(FR-018). */
@Repository
public class PublicationEventRepositoryImpl implements PublicationEventRepository {

    private final LearningPublicationEventJpaRepository eventJpaRepository;
    private final LearningArticleJpaRepository articleJpaRepository;

    public PublicationEventRepositoryImpl(
            LearningPublicationEventJpaRepository eventJpaRepository,
            LearningArticleJpaRepository articleJpaRepository) {
        this.eventJpaRepository = eventJpaRepository;
        this.articleJpaRepository = articleJpaRepository;
    }

    @Override
    public PublicationEvent append(PublicationEvent event) {
        if (event.id() != null) {
            throw new IllegalArgumentException(
                    "events are append-only; cannot save an existing event: id=" + event.id());
        }
        Long articleInternalId =
                PersistenceLookups.requireArticleId(articleJpaRepository, event.articleId());
        LearningPublicationEventJpaEntity saved =
                eventJpaRepository.save(
                        new LearningPublicationEventJpaEntity(
                                articleInternalId,
                                event.action(),
                                event.revisionId(),
                                event.actorUserId(),
                                event.occurredAt()));
        return toDomain(event.articleId(), saved);
    }

    @Override
    public Optional<PublicationEvent> findLastByArticleId(ArticleId articleId) {
        return articleJpaRepository
                .findByArticleKey(articleId.value())
                .flatMap(
                        article ->
                                eventJpaRepository.findFirstByArticleIdOrderByIdDesc(
                                        article.getId()))
                .map(entity -> toDomain(articleId, entity));
    }

    @Override
    public Map<ArticleId, PublicationEvent> findLastForEachArticle() {
        Map<ArticleId, PublicationEvent> events = new HashMap<>();
        for (KeyedEventRow row : eventJpaRepository.findLastForEachArticle()) {
            ArticleId articleId = new ArticleId(row.articleKey());
            events.put(articleId, toDomain(articleId, row.event()));
        }
        return events;
    }

    private static PublicationEvent toDomain(
            ArticleId articleId, LearningPublicationEventJpaEntity entity) {
        return new PublicationEvent(
                entity.getId(),
                articleId,
                entity.getAction(),
                entity.getRevisionId(),
                entity.getActorUserId(),
                entity.getOccurredAt());
    }
}
