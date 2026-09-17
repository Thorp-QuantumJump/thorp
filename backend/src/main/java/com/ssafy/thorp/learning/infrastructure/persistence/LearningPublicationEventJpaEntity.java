package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.PublicationEvent.PublicationAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** {@code learning_publication_event} 매핑. 추가만 하는 기록이라 {@link Immutable}이다. */
@Entity
@Immutable
@Table(name = "learning_publication_event")
public class LearningPublicationEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long articleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private PublicationAction action;

    @Column(updatable = false)
    private Long revisionId;

    @Column(nullable = false, updatable = false)
    private long actorUserId;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected LearningPublicationEventJpaEntity() {}

    LearningPublicationEventJpaEntity(
            Long articleId,
            PublicationAction action,
            Long revisionId,
            long actorUserId,
            Instant occurredAt) {
        this.articleId = articleId;
        this.action = action;
        this.revisionId = revisionId;
        this.actorUserId = actorUserId;
        this.occurredAt = occurredAt;
    }

    Long getId() {
        return id;
    }

    PublicationAction getAction() {
        return action;
    }

    Long getRevisionId() {
        return revisionId;
    }

    long getActorUserId() {
        return actorUserId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
