package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code news_digest} 매핑.
 *
 * <p>여기 저장된 대표 기사·대표 제목·사실 요약·매체 수는 <b>전체 멤버 기준</b> 값이다. 공개 응답은 이 값을 쓰지 않고 허가된 기사만으로 조회 시점에 다시
 * 계산한다. 허가 상태가 바뀌어도 이 행을 덮어쓰지 않으므로 허가가 복원되면 원래 구성이 그대로 살아난다(spec FR-016).
 */
@Entity
@Table(name = "news_digest")
public class NewsDigestJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "news_digest_seq_generator")
    @SequenceGenerator(
            name = "news_digest_seq_generator",
            sequenceName = "news_digest_seq",
            allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String digestId;

    @Column(nullable = false)
    private Long representativeArticleId;

    @Column(nullable = false, length = 1000)
    private String representativeTitle;

    @Column(nullable = false, length = 1200)
    private String factualSummary;

    private Instant firstPublishedAt;

    private Instant latestPublishedAt;

    @Column(nullable = false)
    private int distinctPublisherCount;

    @Column(nullable = false, length = 64)
    private String groupingRuleVersion;

    @Column(nullable = false, length = 64)
    private String importanceRuleVersion;

    protected NewsDigestJpaEntity() {}

    NewsDigestJpaEntity(
            Long id,
            String digestId,
            Long representativeArticleId,
            String representativeTitle,
            String factualSummary,
            Instant firstPublishedAt,
            Instant latestPublishedAt,
            int distinctPublisherCount,
            String groupingRuleVersion,
            String importanceRuleVersion) {
        this.id = id;
        this.digestId = digestId;
        this.representativeArticleId = representativeArticleId;
        this.representativeTitle = representativeTitle;
        this.factualSummary = factualSummary;
        this.firstPublishedAt = firstPublishedAt;
        this.latestPublishedAt = latestPublishedAt;
        this.distinctPublisherCount = distinctPublisherCount;
        this.groupingRuleVersion = groupingRuleVersion;
        this.importanceRuleVersion = importanceRuleVersion;
    }

    public Long getId() {
        return id;
    }

    public String getDigestId() {
        return digestId;
    }

    public Long getRepresentativeArticleId() {
        return representativeArticleId;
    }

    public String getRepresentativeTitle() {
        return representativeTitle;
    }

    public String getFactualSummary() {
        return factualSummary;
    }

    public Instant getFirstPublishedAt() {
        return firstPublishedAt;
    }

    public Instant getLatestPublishedAt() {
        return latestPublishedAt;
    }

    public int getDistinctPublisherCount() {
        return distinctPublisherCount;
    }

    public String getGroupingRuleVersion() {
        return groupingRuleVersion;
    }

    public String getImportanceRuleVersion() {
        return importanceRuleVersion;
    }

    /**
     * 묶음 구성이 바뀌었을 때 전체 멤버 기준 값을 다시 쓴다.
     *
     * <p>허가 상태 변경으로는 부르지 않는다. 그 경우 응답만 다시 계산하고 저장값은 그대로 둔다(spec FR-016).
     */
    void refreshMembership(
            Long representativeArticleId,
            String representativeTitle,
            String factualSummary,
            Instant firstPublishedAt,
            Instant latestPublishedAt,
            int distinctPublisherCount) {
        this.representativeArticleId = representativeArticleId;
        this.representativeTitle = representativeTitle;
        this.factualSummary = factualSummary;
        this.firstPublishedAt = firstPublishedAt;
        this.latestPublishedAt = latestPublishedAt;
        this.distinctPublisherCount = distinctPublisherCount;
    }
}
