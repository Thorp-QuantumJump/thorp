package com.ssafy.thorp.news.domain.model;

import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 매체와 공식 RSS 채널, 현재 표시 허가 상태. 순수 도메인 모델이다.
 *
 * <p>현재 상태만 여기 두고 요청·회신 근거는 {@link NewsPublisherPermissionHistory}에 append-only로 남긴다. 공개 조회가 매번 이력을
 * 접지 않도록 상태를 따로 둔 것이며, 상태 변경과 이력 추가는 한 트랜잭션에서 함께 한다(spec FR-015).
 *
 * <p>공식 채널은 매체가 자사 등록 도메인에서 HTTPS로 제공하는 채널이다. 채널 주소의 host가 등록 도메인 또는 그 하위 도메인이어야 하며 집계·재배포 feed는
 * 등록하지 않는다(spec FR-009).
 *
 * <p>{@code rssUrl}은 서버 전용이다. 응답·로그·지표에 노출하지 않는다(spec FR-021).
 */
public class NewsPublisher {

    private static final Pattern PUBLISHER_KEY = Pattern.compile("^[a-z0-9-]{1,64}$");

    private final Long id;
    private final String publisherKey;
    private final String displayName;
    private final String language;
    private final String registeredDomain;
    private final String rssUrl;
    private final PermissionStatus permissionStatus;
    private final Instant permissionUpdatedAt;
    private final boolean enabled;
    private final String etag;
    private final String lastModified;

    private NewsPublisher(
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
        this.publisherKey = requireKey(publisherKey);
        this.displayName = requireText(displayName, "displayName");
        this.language = requireText(language, "language");
        this.registeredDomain =
                requireText(registeredDomain, "registeredDomain").toLowerCase(Locale.ROOT);
        this.rssUrl = rssUrl;
        this.permissionStatus =
                Objects.requireNonNull(permissionStatus, "permissionStatus must not be null");
        this.permissionUpdatedAt = permissionUpdatedAt;
        this.enabled = enabled;
        this.etag = etag;
        this.lastModified = lastModified;
        if (rssUrl != null && !isOfficialChannel(rssUrl, this.registeredDomain)) {
            throw new IllegalArgumentException("공식 채널이 아니다: " + publisherKey);
        }
    }

    /** 새 매체를 등록한다. 허가 상태는 회신 전이므로 PENDING에서 시작한다. */
    public static NewsPublisher register(
            String publisherKey,
            String displayName,
            String language,
            String registeredDomain,
            String rssUrl,
            boolean enabled) {
        return new NewsPublisher(
                null,
                publisherKey,
                displayName,
                language,
                registeredDomain,
                rssUrl,
                PermissionStatus.PENDING,
                null,
                enabled,
                null,
                null);
    }

    /** 저장된 행을 복원한다. */
    public static NewsPublisher restore(
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
        return new NewsPublisher(
                Objects.requireNonNull(id, "id must not be null"),
                publisherKey,
                displayName,
                language,
                registeredDomain,
                rssUrl,
                permissionStatus,
                permissionUpdatedAt,
                enabled,
                etag,
                lastModified);
    }

    /**
     * 회신을 반영해 현재 허가 상태를 바꾼다. 호출부는 같은 트랜잭션에서 이력도 추가한다(spec FR-015).
     *
     * <p>이력이 근거를 보존하므로 여기서 이전 상태를 남기지 않는다.
     */
    public NewsPublisher withPermission(PermissionStatus status, Instant occurredAt) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        return new NewsPublisher(
                id,
                publisherKey,
                displayName,
                language,
                registeredDomain,
                rssUrl,
                status,
                occurredAt,
                enabled,
                etag,
                lastModified);
    }

    /**
     * 등록표가 소유하는 채널 정보만 갈아 끼운다(spec FR-009).
     *
     * <p><b>허가 상태는 건드리지 않는다.</b> 채널 정보의 원본은 버전 관리되는 등록표 파일이고, 허가 상태의 원본은 매체 회신 이력이다. 기동할 때마다 등록표로 행
     * 전체를 덮으면 이미 받아 둔 회신이 매번 지워져 공개가 조용히 닫힌다 — 되돌리기도 어렵다(spec FR-015·FR-016).
     *
     * <p>조건부 요청 값({@code etag}·{@code lastModified})도 유지한다. 그것은 수집이 쌓은 상태이지 등록표가 아는 값이 아니다.
     */
    public NewsPublisher withChannel(
            String displayName,
            String language,
            String registeredDomain,
            String rssUrl,
            boolean enabled) {
        return new NewsPublisher(
                id,
                publisherKey,
                displayName,
                language,
                registeredDomain,
                rssUrl,
                permissionStatus,
                permissionUpdatedAt,
                enabled,
                etag,
                lastModified);
    }

    /** 조건부 요청 값을 갱신한다. RSS가 304를 주면 cursor와 기사 데이터는 두고 이 값만 유지·갱신한다(spec FR-013). */
    public NewsPublisher withConditionalRequestState(String etag, String lastModified) {
        return new NewsPublisher(
                id,
                publisherKey,
                displayName,
                language,
                registeredDomain,
                rssUrl,
                permissionStatus,
                permissionUpdatedAt,
                enabled,
                etag,
                lastModified);
    }

    /** 401·403처럼 재시도하지 않는 설정 오류를 만나면 비활성화 후보로 내린다(spec FR-013). */
    public NewsPublisher disabled() {
        return new NewsPublisher(
                id,
                publisherKey,
                displayName,
                language,
                registeredDomain,
                rssUrl,
                permissionStatus,
                permissionUpdatedAt,
                false,
                etag,
                lastModified);
    }

    /** 공개 환경에 노출할 수 있는 매체인지 여부(spec FR-016). */
    public boolean isPubliclyVisible() {
        return permissionStatus.isPubliclyVisible();
    }

    /** 이 host가 이 매체의 등록 도메인에 속하는지 확인한다(spec FR-020 allowlist). */
    public boolean ownsHost(String host) {
        if (host == null) {
            return false;
        }
        return isSameOrSubdomain(host.toLowerCase(Locale.ROOT), registeredDomain);
    }

    private static boolean isOfficialChannel(String rssUrl, String registeredDomain) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(rssUrl);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        return isSameOrSubdomain(uri.getHost().toLowerCase(Locale.ROOT), registeredDomain);
    }

    private static boolean isSameOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String requireKey(String publisherKey) {
        requireText(publisherKey, "publisherKey");
        if (!PUBLISHER_KEY.matcher(publisherKey).matches()) {
            throw new IllegalArgumentException("publisherKey는 영문 소문자·숫자·하이픈만 쓴다: " + publisherKey);
        }
        return publisherKey;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없다");
        }
        return value;
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
}
