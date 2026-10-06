package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.infrastructure.persistence.LearningContentJson.SnapshotJson;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code learning_article_revision} 매핑. 불변 게시 판이다.
 *
 * <p>{@link Immutable}로 두고 모든 컬럼을 {@code updatable = false}로 막는다. setter도 없다. 판을 고치는 코드가 생겨도
 * UPDATE가 나가지 않는다(원칙 VIII). 행을 추가하므로 {@code insertable}은 막지 않는다.
 */
@Entity
@Immutable
@Table(name = "learning_article_revision")
public class LearningArticleRevisionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long articleId;

    @Column(nullable = false, updatable = false)
    private int revisionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private SnapshotJson snapshot;

    @Column(nullable = false, length = 64, updatable = false)
    private String checkVersion;

    @Column(nullable = false, length = 128, updatable = false)
    private String sectorMapVersion;

    @Column(nullable = false, updatable = false)
    private long publishedByUserId;

    @Column(nullable = false, updatable = false)
    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private Edition edition;

    protected LearningArticleRevisionJpaEntity() {}

    LearningArticleRevisionJpaEntity(
            Long articleId,
            int revisionNo,
            SnapshotJson snapshot,
            String checkVersion,
            String sectorMapVersion,
            long publishedByUserId,
            Instant publishedAt,
            Edition edition) {
        this.articleId = articleId;
        this.revisionNo = revisionNo;
        this.snapshot = snapshot;
        this.checkVersion = checkVersion;
        this.sectorMapVersion = sectorMapVersion;
        this.publishedByUserId = publishedByUserId;
        this.publishedAt = publishedAt;
        this.edition = edition;
    }

    Long getId() {
        return id;
    }

    Long getArticleId() {
        return articleId;
    }

    int getRevisionNo() {
        return revisionNo;
    }

    SnapshotJson getSnapshot() {
        return snapshot;
    }

    String getCheckVersion() {
        return checkVersion;
    }

    String getSectorMapVersion() {
        return sectorMapVersion;
    }

    long getPublishedByUserId() {
        return publishedByUserId;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Edition getEdition() {
        return edition;
    }
}
