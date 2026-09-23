package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.common.persistence.BaseEntity;
import com.ssafy.thorp.news.domain.vo.PermissionEventType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code news_publisher_permission_history} 매핑(spec FR-015).
 *
 * <p>append-only다. 값을 바꾸는 메서드를 두지 않아 이전 회신 근거가 사라지지 않게 한다.
 *
 * <p>{@code allowedFields}는 PostgreSQL 문자열 배열 컬럼이다. 회신마다 허용 필드 집합이 다르고 내보내기 투영이 이 값을 그대로 쓴다.
 */
@Entity
@Table(name = "news_publisher_permission_history")
public class NewsPublisherPermissionHistoryJpaEntity extends BaseEntity {

    @Id
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "news_publisher_permission_history_seq_generator")
    @SequenceGenerator(
            name = "news_publisher_permission_history_seq_generator",
            sequenceName = "news_publisher_permission_history_seq",
            allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private Long publisherId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PermissionEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PermissionStatus permissionStatus;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] allowedFields;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(columnDefinition = "text")
    private String reference;

    protected NewsPublisherPermissionHistoryJpaEntity() {}

    NewsPublisherPermissionHistoryJpaEntity(
            Long id,
            Long publisherId,
            PermissionEventType eventType,
            PermissionStatus permissionStatus,
            String[] allowedFields,
            Instant occurredAt,
            String reference) {
        this.id = id;
        this.publisherId = publisherId;
        this.eventType = eventType;
        this.permissionStatus = permissionStatus;
        this.allowedFields = allowedFields;
        this.occurredAt = occurredAt;
        this.reference = reference;
    }

    public Long getId() {
        return id;
    }

    public Long getPublisherId() {
        return publisherId;
    }

    public PermissionEventType getEventType() {
        return eventType;
    }

    public PermissionStatus getPermissionStatus() {
        return permissionStatus;
    }

    public String[] getAllowedFields() {
        return allowedFields == null ? new String[0] : allowedFields.clone();
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getReference() {
        return reference;
    }
}
