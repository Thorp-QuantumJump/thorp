package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
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
 * {@code news_publisher} 매핑. 도메인 모델과는 {@link NewsPersistenceMapper}로 변환한다.
 *
 * <p>{@code rssUrl}은 서버 전용이다. 응답 DTO로 새어 나가지 않도록 presentation 계층은 이 엔티티를 직접 쓰지 않는다(spec FR-021).
 */
@Entity
@Table(name = "news_publisher")
public class NewsPublisherJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "news_publisher_seq_generator")
    @SequenceGenerator(
            name = "news_publisher_seq_generator",
            sequenceName = "news_publisher_seq",
            allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String publisherKey;

    @Column(nullable = false, length = 200)
    private String displayName;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(nullable = false, length = 253)
    private String registeredDomain;

    @Column(columnDefinition = "text")
    private String rssUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PermissionStatus permissionStatus;

    private Instant permissionUpdatedAt;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 512)
    private String etag;

    @Column(length = 512)
    private String lastModified;

    protected NewsPublisherJpaEntity() {}

    NewsPublisherJpaEntity(
            Long id,
            String publisherKey,
            String displayName,
            String language,
            String registeredDomain,
            String rssUrl,
            PermissionStatus permissionStatus,
            Instant permissionUpdatedAt,
            boolean enabled,
            String etag,
            String lastModified) {
        this.id = id;
        this.publisherKey = publisherKey;
        this.displayName = displayName;
        this.language = language;
        this.registeredDomain = registeredDomain;
        this.rssUrl = rssUrl;
        this.permissionStatus = permissionStatus;
        this.permissionUpdatedAt = permissionUpdatedAt;
        this.enabled = enabled;
        this.etag = etag;
        this.lastModified = lastModified;
    }

    public Long getId() {
        return id;
    }

    public String getPublisherKey() {
        return publisherKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLanguage() {
        return language;
    }

    public String getRegisteredDomain() {
        return registeredDomain;
    }

    public String getRssUrl() {
        return rssUrl;
    }

    public PermissionStatus getPermissionStatus() {
        return permissionStatus;
    }

    public Instant getPermissionUpdatedAt() {
        return permissionUpdatedAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    /** 허가 상태를 갱신한다. 이력 추가와 같은 트랜잭션에서 불린다(spec FR-015). */
    void applyPermission(PermissionStatus status, Instant occurredAt) {
        this.permissionStatus = status;
        this.permissionUpdatedAt = occurredAt;
    }

    /** 조건부 요청 값을 갱신한다. */
    void applyConditionalRequestState(String etag, String lastModified) {
        this.etag = etag;
        this.lastModified = lastModified;
    }

    void disable() {
        this.enabled = false;
    }
}
