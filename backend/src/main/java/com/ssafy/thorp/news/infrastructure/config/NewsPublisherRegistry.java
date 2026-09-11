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
     */
    public record Publisher(
            String publisherKey,
            String displayName,
            String language,
            String registeredDomain,
            String rssUrl,
            boolean enabled) {

        private static final java.util.regex.Pattern KEY =
                java.util.regex.Pattern.compile("^[a-z0-9-]{1,64}$");

        public Publisher {
            requireText(publisherKey, "publisherKey");
            requireText(displayName, "displayName");
            requireText(language, "language");
            requireText(registeredDomain, "registeredDomain");
            requireText(rssUrl, "rssUrl");
            if (!KEY.matcher(publisherKey).matches()) {
                throw new IllegalArgumentException(
                        "publisherKey는 영문 소문자·숫자·하이픈만 쓴다: " + publisherKey);
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
