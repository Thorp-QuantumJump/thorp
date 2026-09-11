package com.ssafy.thorp.news.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * {@code news_article_sector} 매핑. 기사와 섹터의 연결이다.
 *
 * <p>{@code sectorId}는 spec 001이 소유하는 외부 식별자다. 012는 섹터 마스터 테이블을 만들지 않고 값을 참조만 하므로 FK를 걸지 않는다. 등록되지
 * 않은 값으로 들어온 조회는 상위 계층이 거부한다(spec FR-005).
 *
 * <p>연결 규칙 버전을 키에 넣어 규칙이 바뀌어도 기존 연결을 덮어쓰지 않는다(원칙 VIII).
 */
@Entity
@Table(name = "news_article_sector")
public class NewsArticleSectorJpaEntity {

    @EmbeddedId private Key key;

    protected NewsArticleSectorJpaEntity() {}

    NewsArticleSectorJpaEntity(Key key) {
        this.key = key;
    }

    public Key getKey() {
        return key;
    }

    /** 복합 키. 같은 기사·섹터라도 규칙 버전이 다르면 별개 연결이다. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "article_id", nullable = false)
        private Long articleId;

        @Column(name = "sector_id", nullable = false, length = 64)
        private String sectorId;

        @Column(name = "rule_version", nullable = false, length = 64)
        private String ruleVersion;

        protected Key() {}

        public Key(Long articleId, String sectorId, String ruleVersion) {
            this.articleId = articleId;
            this.sectorId = sectorId;
            this.ruleVersion = ruleVersion;
        }

        public Long getArticleId() {
            return articleId;
        }

        public String getSectorId() {
            return sectorId;
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
                    && Objects.equals(sectorId, key.sectorId)
                    && Objects.equals(ruleVersion, key.ruleVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(articleId, sectorId, ruleVersion);
        }
    }
}
