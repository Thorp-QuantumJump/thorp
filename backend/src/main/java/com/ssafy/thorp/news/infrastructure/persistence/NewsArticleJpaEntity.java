package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.news.domain.vo.NewsSourceType;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code news_article} 매핑.
 *
 * <p><b>본문 컬럼이 없다.</b> 본문·HTML·이미지·RSS description에 해당하는 필드를 두지 않는 것으로 본문이 영속화 경로에 유입되는 것을 스키마 수준에서
 * 막는다(spec FR-002).
 *
 * <p>{@code publishedAt}은 {@code publishedAtStatus}가 VERIFIED일 때만 값이 있다. DB CHECK 제약이 같은 규칙을 강제한다.
 */
@Entity
@Table(name = "news_article")
public class NewsArticleJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "news_article_seq_generator")
    @SequenceGenerator(
            name = "news_article_seq_generator",
            sequenceName = "news_article_seq",
            allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String articleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NewsSourceType sourceType;

    @Column(nullable = false)
    private Long publisherId;

    @Column(length = 512)
    private String sourceItemId;

    @Column(nullable = false, columnDefinition = "text")
    private String originalUrl;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String canonicalUrl;

    @Column(nullable = false, length = 1000)
    private String title;

    @Column(nullable = false, length = 16)
    private String language;

    private Instant publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PublicationTimeStatus publishedAtStatus;

    @Column(nullable = false)
    private Instant collectedAt;

    private Instant gdeltRecordedAt;

    @Column(nullable = false, length = 64)
    private String duplicateKey;

    @Column(nullable = false, length = 64)
    private String sourcePayloadHash;

    @Column(nullable = false, length = 40)
    private String ingestRunId;

    @Column(nullable = false)
    private boolean displayEligible;

    protected NewsArticleJpaEntity() {}

    NewsArticleJpaEntity(
            Long id,
            String articleId,
            NewsSourceType sourceType,
            Long publisherId,
            String sourceItemId,
            String originalUrl,
            String canonicalUrl,
            String title,
            String language,
            Instant publishedAt,
            PublicationTimeStatus publishedAtStatus,
            Instant collectedAt,
            Instant gdeltRecordedAt,
            String duplicateKey,
            String sourcePayloadHash,
            String ingestRunId,
            boolean displayEligible) {
        this.id = id;
        this.articleId = articleId;
        this.sourceType = sourceType;
        this.publisherId = publisherId;
        this.sourceItemId = sourceItemId;
        this.originalUrl = originalUrl;
        this.canonicalUrl = canonicalUrl;
        this.title = title;
        this.language = language;
        this.publishedAt = publishedAt;
        this.publishedAtStatus = publishedAtStatus;
        this.collectedAt = collectedAt;
        this.gdeltRecordedAt = gdeltRecordedAt;
        this.duplicateKey = duplicateKey;
        this.sourcePayloadHash = sourcePayloadHash;
        this.ingestRunId = ingestRunId;
        this.displayEligible = displayEligible;
    }

    public Long getId() {
        return id;
    }

    public String getArticleId() {
        return articleId;
    }

    public NewsSourceType getSourceType() {
        return sourceType;
    }

    public Long getPublisherId() {
        return publisherId;
    }

    public String getSourceItemId() {
        return sourceItemId;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getLanguage() {
        return language;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public PublicationTimeStatus getPublishedAtStatus() {
        return publishedAtStatus;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getGdeltRecordedAt() {
        return gdeltRecordedAt;
    }

    public String getDuplicateKey() {
        return duplicateKey;
    }

    public String getSourcePayloadHash() {
        return sourcePayloadHash;
    }

    public String getIngestRunId() {
        return ingestRunId;
    }

    public boolean isDisplayEligible() {
        return displayEligible;
    }

    /**
     * 같은 canonical URL을 다시 만났을 때 최신 메타데이터를 반영한다.
     *
     * <p>최초 수집 시각과 외부 식별자는 유지한다. 그 값들이 바뀌면 재현 기록과 외부 참조가 깨진다(spec FR-011·FR-025).
     */
    void refreshMetadata(
            String title,
            Instant publishedAt,
            PublicationTimeStatus publishedAtStatus,
            Instant gdeltRecordedAt,
            String sourcePayloadHash,
            String ingestRunId,
            boolean displayEligible) {
        this.title = title;
        this.publishedAt = publishedAt;
        this.publishedAtStatus = publishedAtStatus;
        this.gdeltRecordedAt = gdeltRecordedAt;
        this.sourcePayloadHash = sourcePayloadHash;
        this.ingestRunId = ingestRunId;
        this.displayEligible = displayEligible;
    }
}
