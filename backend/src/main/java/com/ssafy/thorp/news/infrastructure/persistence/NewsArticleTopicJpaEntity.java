package com.ssafy.thorp.news.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * {@code news_article_topic} 매핑. 기사와 등록된 경제 주제의 연결이다.
 *
 * <p>뉴스원이 준 원문 분류 값은 저장하지 않는다. 등록표 매핑을 통과한 주제만 연결로 남기며, 매핑되지 않은 분류 값은 연결 근거로 쓰지 않고 미등록 건수 지표로만
 * 센다(spec FR-010).
 *
 * <p>{@code matchEvidenceKey}는 어떤 규칙 항목이 걸렸는지 가리키는 키다. 기사 본문 조각을 담지 않는다.
 */
@Entity
@Table(name = "news_article_topic")
public class NewsArticleTopicJpaEntity {

    @EmbeddedId private Key key;

    @Column(length = 128)
    private String matchEvidenceKey;

    protected NewsArticleTopicJpaEntity() {}

    NewsArticleTopicJpaEntity(Key key, String matchEvidenceKey) {
        this.key = key;
        this.matchEvidenceKey = matchEvidenceKey;
    }

    public Key getKey() {
        return key;
    }

    public String getMatchEvidenceKey() {
        return matchEvidenceKey;
    }

    /** 복합 키. 같은 기사·주제라도 규칙 버전이 다르면 별개 연결이다. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "article_id", nullable = false)
        private Long articleId;

        @Column(name = "topic_id", nullable = false)
        private Long topicId;

        @Column(name = "rule_version", nullable = false, length = 64)
        private String ruleVersion;

        protected Key() {}

        public Key(Long articleId, Long topicId, String ruleVersion) {
            this.articleId = articleId;
            this.topicId = topicId;
            this.ruleVersion = ruleVersion;
        }

        public Long getArticleId() {
            return articleId;
        }

        public Long getTopicId() {
            return topicId;
        }

        public String getRuleVersion() {
            return ruleVersion;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(articleId, key.articleId)
                    && Objects.equals(topicId, key.topicId)
                    && Objects.equals(ruleVersion, key.ruleVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(articleId, topicId, ruleVersion);
        }
    }
}
