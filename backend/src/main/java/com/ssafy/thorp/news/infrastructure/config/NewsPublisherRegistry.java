package com.ssafy.thorp.news.infrastructure.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 버전 관리되는 매체·RSS 채널 등록표(spec FR-009).
 *
 * <p>공식 채널은 매체가 자사 등록 도메인에서 HTTPS로 제공하고 이 등록표에 있는 채널이다. 채널 주소의 host가 매체의 등록 도메인 또는 그 하위 도메인이어야 하며
 * 집계·재배포 feed는 등록하지 않는다.
 *
 * <p>실제 매체 항목은 BACK 파트가 공개 전에 채운다. 항목이 비어 있어도 구현과 내부 검증은 진행하며, 이때 공개 조회는 PREPARING을 반환한다(spec
 * Assumptions, PD-15a).
 *
 * @param version 등록표 버전 식별자.
 * @param publishers 등록된 매체 목록. 비어 있을 수 있다.
 */
public record NewsPublisherRegistry(String version, List<Publisher> publishers) {

    /**
     * 등록된 매체 하나.
     *
     * @param publisherKey 매체 식별 키. 영문 소문자와 하이픈만 쓴다.
     * @param displayName 화면에 보이는 매체 이름.
     * @param language BCP 47 언어 태그.
     * @param registeredDomain 공식 채널 판정 기준이 되는 매체 등록 도메인.
     * @param rssUrl 공식 RSS 채널 주소. 서버 전용이며 응답·로그에 노출하지 않는다(spec FR-021).
     * @param enabled 수집 활성화 여부.
     * @param topicKeys 이 채널이 다루는 주제. 기사가 분류 값을 주지 않는 매체에만 적는다.
     * @param permissionRequest 매체에 허가를 요청한 사실. 요청하지 않았으면 null이다.
     * @param permission 매체가 보낸 허가 회신. 아직 회신이 없으면 null이고 그 매체는 {@code PENDING}으로 남는다.
     */
    public record Publisher(
            String publisherKey,
            String displayName,
            String language,
            String registeredDomain,
            String rssUrl,
            boolean enabled,
            java.util.List<String> topicKeys,
            PermissionRequest permissionRequest,
            Permission permission) {

        /** 채널 주제도 회신도 적지 않은 매체. */
        public Publisher(
                String publisherKey,
                String displayName,
                String language,
                String registeredDomain,
                String rssUrl,
                boolean enabled,
                java.util.List<String> topicKeys) {
            this(
                    publisherKey,
                    displayName,
                    language,
                    registeredDomain,
                    rssUrl,
                    enabled,
                    topicKeys,
                    null,
                    null);
        }

        /** 기사 분류 값을 주는 매체. 채널 주제를 따로 적지 않는다. */
        public Publisher(
                String publisherKey,
                String displayName,
                String language,
                String registeredDomain,
                String rssUrl,
                boolean enabled) {
            this(
                    publisherKey,
                    displayName,
                    language,
                    registeredDomain,
                    rssUrl,
                    enabled,
                    java.util.List.of());
        }

        private static final java.util.regex.Pattern KEY =
                java.util.regex.Pattern.compile("^[a-z0-9-]{1,64}$");

        public Publisher {
            topicKeys = topicKeys == null ? java.util.List.of() : java.util.List.copyOf(topicKeys);
            requireText(publisherKey, "publisherKey");
            requireText(displayName, "displayName");
            requireText(language, "language");
            requireText(registeredDomain, "registeredDomain");
            requireText(rssUrl, "rssUrl");
            if (!KEY.matcher(publisherKey).matches()) {
                throw new IllegalArgumentException(
                        "publisherKey는 영문 소문자·숫자·하이픈만 쓴다: " + publisherKey);
            }
            // 답이 요청보다 먼저 올 수는 없다. 시각을 잘못 적으면 이력의 앞뒤가 뒤집혀 현재 상태가
            // 요청(PENDING)으로 굳는다 — 허가받은 매체가 조용히 공개에서 빠진다.
            if (permissionRequest != null
                    && permission != null
                    && permission.respondedAt().isBefore(permissionRequest.requestedAt())) {
                throw new IllegalArgumentException("회신 시각이 요청 시각보다 빠르다: " + publisherKey);
            }
            registeredDomain = registeredDomain.toLowerCase(Locale.ROOT);
            String host = hostOf(rssUrl);
            if (!isSameOrSubdomain(host, registeredDomain)) {
                throw new IllegalArgumentException(
                        "공식 채널이 아니다. rssUrl host가 등록 도메인과 다르다: "
                                + publisherKey
                                + " host="
                                + host
                                + " domain="
                                + registeredDomain);
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + "는 비어 있을 수 없다");
            }
        }

        /** rssUrl의 host를 꺼낸다. HTTPS가 아니면 등록을 거부한다. */
        private static String hostOf(String rssUrl) {
            java.net.URI uri;
            try {
                uri = java.net.URI.create(rssUrl);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("rssUrl을 해석할 수 없다");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("공식 채널은 HTTPS만 허용한다: " + uri.getScheme());
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("rssUrl에 host가 없다");
            }
            return uri.getHost().toLowerCase(Locale.ROOT);
        }

        private static boolean isSameOrSubdomain(String host, String domain) {
            return host.equals(domain) || host.endsWith("." + domain);
        }
    }

    /**
     * 매체가 보낸 허가 회신(spec FR-015, PD-15).
     *
     * <p><b>회신을 여기 적는 이유.</b> 이력과 현재 상태의 원본은 저장소지만, 회신을 저장소에 넣을 방법이 없으면 운영 DB에 직접 SQL을 치게 된다. 그러면
     * 누가 언제 무엇을 근거로 공개를 열었는지 코드 이력에 남지 않는다. 회신은 매체에 관한 사실이므로 매체 등록표에 함께 둔다.
     *
     * <p>적는 것과 반영하는 것은 다르다. 동기화는 이 값을 매체 행에 직접 쓰지 않고 허가 이력을 통해 반영한다 — append-only와 원자적 전이가 그대로
     * 지켜지고, 회신을 받은 사실 자체가 이력에 남는다.
     *
     * <p>회신이 없는 매체는 이 항목을 적지 않는다. {@code PENDING}을 적는 자리가 아니다 — 그것은 회신이 없다는 뜻이고, 없는 것을 적어 둘 이유가 없다.
     *
     * @param status 회신 결과. {@code ALLOWED} 또는 {@code DENIED}만 쓴다.
     * @param allowedFields 허용된 필드. {@code ALLOWED}면 하나 이상이어야 한다.
     * @param respondedAt 회신을 받은 시각. 같은 회신을 두 번 반영하지 않는 기준이 된다.
     * @param reference 회신의 근거. 메일 제목이나 문서 번호처럼 나중에 찾아갈 수 있는 것을 적는다.
     */
    /**
     * 매체에 허가를 요청한 사실(spec FR-015).
     *
     * <p><b>회신과 따로 적는다.</b> 요청했는데 답이 없는 것과 아예 요청하지 않은 것은 전혀 다른 상태인데, 회신만 적을 수 있으면 둘이 똑같이 빈칸으로 보인다.
     * 프로젝트가 끝날 때 "우리가 요청은 했는가"에 답하려면 이 자리가 있어야 한다.
     *
     * <p>요청은 매체의 현재 상태를 {@code PENDING}으로 둔다. 요청했다는 것과 공개해도 된다는 것은 다르다.
     *
     * @param requestedAt 요청을 보낸 시각.
     * @param reference 요청의 근거. 메일 제목이나 문서 번호처럼 나중에 찾아갈 수 있는 것을 적는다.
     */
    public record PermissionRequest(java.time.Instant requestedAt, String reference) {

        public PermissionRequest {
            java.util.Objects.requireNonNull(requestedAt, "requestedAt");
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("reference는 비어 있을 수 없다. 요청의 근거를 적는다");
            }
        }
    }

    public record Permission(
            com.ssafy.thorp.news.domain.vo.PermissionStatus status,
            java.util.List<com.ssafy.thorp.news.domain.vo.NewsAllowedField> allowedFields,
            java.time.Instant respondedAt,
            String reference) {

        public Permission {
            java.util.Objects.requireNonNull(status, "status");
            java.util.Objects.requireNonNull(respondedAt, "respondedAt");
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("reference는 비어 있을 수 없다. 회신의 근거를 적는다");
            }
            allowedFields =
                    allowedFields == null
                            ? java.util.List.of()
                            : java.util.List.copyOf(allowedFields);
            if (status == com.ssafy.thorp.news.domain.vo.PermissionStatus.PENDING) {
                throw new IllegalArgumentException("회신이 없는 매체는 permission을 적지 않는다. PENDING이 기본값이다");
            }
            if (status == com.ssafy.thorp.news.domain.vo.PermissionStatus.ALLOWED
                    && allowedFields.isEmpty()) {
                throw new IllegalArgumentException("허가 회신에는 허용 필드가 하나 이상 있어야 한다");
            }
        }
    }

    public NewsPublisherRegistry {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version은 비어 있을 수 없다");
        }
        publishers = publishers == null ? List.of() : List.copyOf(publishers);
        long distinct = publishers.stream().map(Publisher::publisherKey).distinct().count();
        if (distinct != publishers.size()) {
            throw new IllegalArgumentException("publisherKey가 중복됐다");
        }
    }

    /** 수집 대상으로 활성화된 매체만 돌려준다. */
    public List<Publisher> enabledPublishers() {
        return publishers.stream().filter(Publisher::enabled).toList();
    }

    /** 수집 응답의 host가 등록된 매체의 것인지 확인한다(spec FR-020 allowlist). */
    public Optional<Publisher> findByHost(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String lower = host.toLowerCase(Locale.ROOT);
        return publishers.stream()
                .filter(p -> Publisher.isSameOrSubdomain(lower, p.registeredDomain()))
                .findFirst();
    }

    public Optional<Publisher> findByKey(String publisherKey) {
        return publishers.stream().filter(p -> p.publisherKey().equals(publisherKey)).findFirst();
    }
}
