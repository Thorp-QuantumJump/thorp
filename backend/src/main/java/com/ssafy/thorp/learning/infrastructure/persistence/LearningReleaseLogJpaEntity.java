package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry.Kind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** {@code learning_release_log} 매핑. 추가만 하는 기록이라 {@link Immutable}이다. 단계·글은 대리키 컬럼만 둔다. */
@Entity
@Immutable
@Table(name = "learning_release_log")
public class LearningReleaseLogJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, updatable = false)
    private String entryKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private Kind kind;

    @Column(nullable = false, updatable = false)
    private Long stageId;

    @Column(updatable = false)
    private Long articleId;

    @Column(nullable = false, updatable = false)
    private short newCount;

    @Column(nullable = false, updatable = false)
    private short rewrittenCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private List<String> rewrittenTitles;

    @Column(length = 100, updatable = false)
    private String articleTitle;

    @Column(nullable = false, updatable = false)
    private long actorUserId;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected LearningReleaseLogJpaEntity() {}

    LearningReleaseLogJpaEntity(
            String entryKey,
            Kind kind,
            Long stageId,
            Long articleId,
            int newCount,
            int rewrittenCount,
            List<String> rewrittenTitles,
            String articleTitle,
            long actorUserId,
            Instant occurredAt) {
        this.entryKey = entryKey;
        this.kind = kind;
        this.stageId = stageId;
        this.articleId = articleId;
        this.newCount = (short) newCount;
        this.rewrittenCount = (short) rewrittenCount;
        this.rewrittenTitles = rewrittenTitles;
        this.articleTitle = articleTitle;
        this.actorUserId = actorUserId;
        this.occurredAt = occurredAt;
    }

    String getEntryKey() {
        return entryKey;
    }

    Kind getKind() {
        return kind;
    }

    Long getStageId() {
        return stageId;
    }

    Long getArticleId() {
        return articleId;
    }

    int getNewCount() {
        return newCount;
    }

    int getRewrittenCount() {
        return rewrittenCount;
    }

    List<String> getRewrittenTitles() {
        return rewrittenTitles;
    }

    String getArticleTitle() {
        return articleTitle;
    }

    long getActorUserId() {
        return actorUserId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
