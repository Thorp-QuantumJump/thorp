package com.ssafy.thorp.news.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * {@code news_digest_article} 매핑. 묶음과 기사의 N:M 멤버십이다.
 *
 * <p>묶음 근거로 시간 차·제목 유사도·공통 주제 키를 남긴다. GDELT Tone과 본문 근거는 남기지 않는다(spec FR-004, Out of Scope).
 */
@Entity
@Table(name = "news_digest_article")
public class NewsDigestArticleJpaEntity {

    @EmbeddedId private Key key;

    private Long timeGapSeconds;

    private Double titleSimilarity;

    @Column(length = 64)
    private String sharedTopicKey;

    protected NewsDigestArticleJpaEntity() {}

    NewsDigestArticleJpaEntity(
            Key key, Long timeGapSeconds, Double titleSimilarity, String sharedTopicKey) {
        this.key = key;
        this.timeGapSeconds = timeGapSeconds;
        this.titleSimilarity = titleSimilarity;
        this.sharedTopicKey = sharedTopicKey;
    }

    public Key getKey() {
        return key;
    }

    public Long getTimeGapSeconds() {
        return timeGapSeconds;
    }

    public Double getTitleSimilarity() {
        return titleSimilarity;
    }

    public String getSharedTopicKey() {
        return sharedTopicKey;
    }

    /** 복합 키. 한 기사는 한 묶음에 한 번만 들어간다. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "digest_id", nullable = false)
        private Long digestId;

        @Column(name = "article_id", nullable = false)
        private Long articleId;

        protected Key() {}

        public Key(Long digestId, Long articleId) {
            this.digestId = digestId;
            this.articleId = articleId;
        }

        public Long getDigestId() {
            return digestId;
        }

        public Long getArticleId() {
            return articleId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(digestId, key.digestId)
                    && Objects.equals(articleId, key.articleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(digestId, articleId);
        }
    }
}
