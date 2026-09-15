package com.ssafy.thorp.news.domain.model;

import com.ssafy.thorp.news.domain.vo.NewsAllowedField;
import com.ssafy.thorp.news.domain.vo.PermissionEventType;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 매체 허가 요청과 회신의 append-only 기록(spec FR-015).
 *
 * <p>여러 번 주고받은 근거를 잃지 않도록 사건마다 한 행을 더한다. 기존 행을 고치거나 지우는 메서드를 두지 않는다.
 *
 * <p>{@code allowedFields}는 그 회신에서 허용된 필드 집합이다. 내보내기 투영이 이 값을 쓰며 여기에 없는 필드는 반환하지 않는다(spec FR-017).
 * 기사 본문은 어떤 회신으로도 허용되지 않는다.
 *
 * <p>{@code reference}는 회신의 위치나 식별자다. 기사 본문이나 외부 응답 원문을 담지 않는다.
 */
public record NewsPublisherPermissionHistory(
        Long id,
        Long publisherId,
        PermissionEventType eventType,
        PermissionStatus permissionStatus,
        Set<NewsAllowedField> allowedFields,
        Instant occurredAt,
        String reference) {

    public NewsPublisherPermissionHistory {
        Objects.requireNonNull(publisherId, "publisherId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(permissionStatus, "permissionStatus must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (!eventType.allows(permissionStatus)) {
            throw new IllegalArgumentException(
                    "이 사건 종류에 쓸 수 없는 허가 상태다: " + eventType + " / " + permissionStatus);
        }
        Set<NewsAllowedField> fields =
                allowedFields == null || allowedFields.isEmpty()
                        ? Collections.emptySet()
                        : Collections.unmodifiableSet(EnumSet.copyOf(allowedFields));
        if (permissionStatus != PermissionStatus.ALLOWED && !fields.isEmpty()) {
            throw new IllegalArgumentException("허가 회신이 아니면 허용 필드를 기록하지 않는다");
        }
        if (permissionStatus == PermissionStatus.ALLOWED && fields.isEmpty()) {
            throw new IllegalArgumentException("허가 회신에는 허용 필드가 하나 이상 있어야 한다");
        }
        allowedFields = fields;
    }

    /** 매체에 표시 방식 허가를 요청한 사건을 기록한다. */
    public static NewsPublisherPermissionHistory requested(
            Long publisherId, Instant occurredAt, String reference) {
        return new NewsPublisherPermissionHistory(
                null,
                publisherId,
                PermissionEventType.REQUESTED,
                PermissionStatus.PENDING,
                Collections.emptySet(),
                occurredAt,
                reference);
    }

    /**
     * 허가 회신을 기록한다. 허용 필드를 함께 남긴다.
     *
     * <p><b>허용 필드가 비면 받지 않는다.</b> 보여 줄 것이 하나도 없는 허가는 불허와 같은데 상태만 {@code ALLOWED}로 남는다. 그러면 공개 조회가 그
     * 매체를 노출 대상으로 세고, 투영 단계에서 필드가 전부 빠져 제목도 매체명도 없는 항목이 만들어진다. 불허라면 {@link #denied}를 쓴다.
     */
    public static NewsPublisherPermissionHistory allowed(
            Long publisherId,
            Set<NewsAllowedField> allowedFields,
            Instant occurredAt,
            String reference) {
        if (allowedFields == null || allowedFields.isEmpty()) {
            throw new IllegalArgumentException("허가 회신에는 허용 필드가 하나 이상 있어야 한다");
        }
        return new NewsPublisherPermissionHistory(
                null,
                publisherId,
                PermissionEventType.RESPONDED,
                PermissionStatus.ALLOWED,
                allowedFields,
                occurredAt,
                reference);
    }

    /** 불허 회신을 기록한다. 허용 필드는 없다. */
    public static NewsPublisherPermissionHistory denied(
            Long publisherId, Instant occurredAt, String reference) {
        return new NewsPublisherPermissionHistory(
                null,
                publisherId,
                PermissionEventType.RESPONDED,
                PermissionStatus.DENIED,
                Collections.emptySet(),
                occurredAt,
                reference);
    }

    /** 이 회신이 그 필드의 노출을 허용하는지 확인한다. */
    public boolean allows(NewsAllowedField field) {
        return allowedFields.contains(field);
    }
}
