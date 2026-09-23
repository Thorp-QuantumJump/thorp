package com.ssafy.thorp.news.domain.vo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 같은 원문을 가리키는 URL 변형을 하나로 합치기 위한 정규화된 URL(spec FR-011).
 *
 * <p>규칙 {@code news-url-normalization-v1}: scheme·host 소문자화, 기본 포트 제거, fragment 제거, 빈 path의 {@code
 * /} 통일, 루트 외 후행 {@code /} 제거, query 이름·값 정렬, 등록된 추적 parameter 제거. 그 밖의 query 값과 path 대소문자는 보존한다.
 *
 * <p>정규화할 수 없는 절대 URL은 수집에서 제외한다. 여기서는 예외로 알리고, 호출부가 오류 분류만 기록하고 그 항목을 버린다.
 *
 * <p>이 값이 기사 외부 식별자의 사업 키이므로 같은 입력에 항상 같은 결과를 내야 한다. 규칙을 바꿀 때는 값을 덮어쓰지 않고 새 규칙 버전을 만든다.
 */
public record CanonicalNewsUrl(String value) {

    /** 규칙 버전 식별자. 저장된 기사와 함께 남겨 재현에 쓴다. */
    public static final String RULE_VERSION = "news-url-normalization-v1";

    /** 규칙 버전이 등록한 추적 parameter. 값에 관계없이 제거한다. */
    private static final Set<String> TRACKING_PARAMETERS =
            Set.of(
                    "utm_source",
                    "utm_medium",
                    "utm_campaign",
                    "utm_term",
                    "utm_content",
                    "gclid",
                    "fbclid");

    public CanonicalNewsUrl {
        Objects.requireNonNull(value, "canonicalUrl must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("canonicalUrl은 비어 있을 수 없다");
        }
    }

    /**
     * 절대 URL을 정규화한다. 정규화할 수 없으면 {@link IllegalArgumentException}을 던진다.
     *
     * <p>여기 들어오는 값은 허용된 redirect를 모두 따른 최종 URL이어야 한다. redirect 추적과 host allowlist 검사는 수집 계층의 책임이다.
     */
    public static CanonicalNewsUrl normalize(String absoluteUrl) {
        if (absoluteUrl == null || absoluteUrl.isBlank()) {
            throw new IllegalArgumentException("정규화할 URL이 없다");
        }
        URI uri = parse(absoluteUrl.trim());
        String scheme = requireScheme(uri);
        String host = requireHost(uri);
        int port = normalizePort(scheme, uri.getPort());
        String path = normalizePath(uri.getRawPath());
        String query = normalizeQuery(uri.getRawQuery());

        StringBuilder out = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            out.append(':').append(port);
        }
        out.append(path);
        if (query != null) {
            out.append('?').append(query);
        }
        return new CanonicalNewsUrl(out.toString());
    }

    private static URI parse(String absoluteUrl) {
        try {
            URI uri = new URI(absoluteUrl);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("절대 URL이 아니다");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL을 해석할 수 없다", e);
        }
    }

    private static String requireScheme(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("scheme이 없다");
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!lower.equals("http") && !lower.equals("https")) {
            throw new IllegalArgumentException("http 또는 https만 정규화한다: " + lower);
        }
        return lower;
    }

    private static String requireHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host가 없다");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** 기본 포트는 제거한다. 그 밖의 포트는 보존한다. */
    private static int normalizePort(String scheme, int port) {
        if (port == -1) {
            return -1;
        }
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            return -1;
        }
        return port;
    }

    /** 빈 path는 {@code /}로 통일하고, 루트가 아닌 path의 후행 {@code /}는 제거한다. 대소문자는 보존한다. */
    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        if (rawPath.equals("/")) {
            return "/";
        }
        String path = rawPath;
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * 추적 parameter를 빼고 남은 query를 이름·값 순으로 정렬한다. 남는 parameter가 없으면 query 자체를 없앤다.
     *
     * <p>parameter 이름의 대소문자는 비교에만 낮춰 쓰고 출력에는 원래 표기를 남긴다. 값은 그대로 보존한다.
     */
    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            if (TRACKING_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            kept.add(pair);
        }
        if (kept.isEmpty()) {
            return null;
        }
        kept.sort(CanonicalNewsUrl::comparePairs);
        return String.join("&", kept);
    }

    /** 이름을 먼저, 같으면 값을 비교한다. 같은 입력 집합이면 순서가 달라도 같은 결과가 나온다. */
    private static int comparePairs(String left, String right) {
        String leftName = nameOf(left);
        String rightName = nameOf(right);
        int byName = leftName.compareTo(rightName);
        return byName != 0 ? byName : valueOf(left).compareTo(valueOf(right));
    }

    private static String nameOf(String pair) {
        int eq = pair.indexOf('=');
        return eq >= 0 ? pair.substring(0, eq) : pair;
    }

    private static String valueOf(String pair) {
        int eq = pair.indexOf('=');
        return eq >= 0 ? pair.substring(eq + 1) : "";
    }
}
