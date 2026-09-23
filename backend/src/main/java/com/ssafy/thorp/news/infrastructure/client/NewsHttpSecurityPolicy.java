package com.ssafy.thorp.news.infrastructure.client;

import com.ssafy.thorp.news.application.port.NewsSourceClient;
import com.ssafy.thorp.news.infrastructure.config.NewsProperties;
import com.ssafy.thorp.news.infrastructure.config.NewsPublisherRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import org.springframework.stereotype.Component;

/**
 * 외부 뉴스원 호출의 공통 보안 경계(spec FR-020). RSS·GDELT adapter는 반드시 이 정책을 통과한 뒤에만 호출한다.
 *
 * <p>한도는 {@link NewsProperties.Http}가 들고 있고 그 값은 설정으로 더 엄격하게만 조일 수 있다. 여기서는 판정만 한다.
 *
 * <p>오류는 분류 코드만 담는다. 주소·인증 정보·응답 원문을 메시지에 넣지 않는다(spec FR-021). 호출부는 이 코드를 {@code
 * NewsCollectionState.errorCategory}에 그대로 기록할 수 있다.
 */
@Component
public class NewsHttpSecurityPolicy {

    /** https가 아닌 scheme. */
    public static final String INSECURE_SCHEME = "INSECURE_SCHEME";

    /** 등록되지 않은 host. */
    public static final String HOST_NOT_ALLOWED = "HOST_NOT_ALLOWED";

    /** redirect 한도 초과. */
    public static final String TOO_MANY_REDIRECTS = "TOO_MANY_REDIRECTS";

    /** 허용하지 않는 content-type. */
    public static final String UNSUPPORTED_CONTENT_TYPE = "UNSUPPORTED_CONTENT_TYPE";

    /** 해석할 수 없는 주소. */
    public static final String MALFORMED_URL = "MALFORMED_URL";

    /** 응답 본문이 한도를 넘었다. */
    public static final String RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";

    /** 배포 파일은 구분자 텍스트로 온다. DOC API의 JSON 한도와 섞지 않으려고 따로 둔다. */
    /**
     * GDELT 배포 파일의 content-type.
     *
     * <p>GDELT 2.0은 배포 파일을 zip으로만 내려 준다. 서버마다 zip을 가리키는 이름이 갈려 세 가지를 모두 받는다. 압축을 풀어 얻는 것은 탭으로 나뉜
     * 표라, 압축 없이 그대로 주는 경우까지 열어 둔다.
     */
    private static final Set<String> EXPORT_FILE_CONTENT_TYPES =
            Set.of(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "text/csv",
                    "text/tab-separated-values",
                    "text/plain");

    private final NewsProperties.Http config;
    private final NewsPublisherRegistry publishers;

    // 생성자가 둘이라 어느 쪽으로 주입할지 명시한다. 없으면 Spring이 기본 생성자를 찾다가 실패한다.
    @org.springframework.beans.factory.annotation.Autowired
    public NewsHttpSecurityPolicy(NewsProperties properties, NewsPublisherRegistry publishers) {
        this(properties.http(), publishers);
    }

    /** 테스트가 한도를 직접 넣을 수 있게 열어 둔다. */
    public NewsHttpSecurityPolicy(NewsProperties.Http config, NewsPublisherRegistry publishers) {
        this.config = config;
        this.publishers = publishers;
    }

    /** 뉴스원 종류. 허용 host와 content-type이 종류마다 다르다. */
    public enum SourceKind {
        RSS,
        GDELT_DOC,
        GDELT_FILE
    }

    /** 최초 요청 주소를 검사한다. */
    public URI requireAllowedUrl(String url, SourceKind kind) {
        return requireAllowed(parse(url), kind);
    }

    /**
     * redirect 목적지를 검사한다. hop마다 scheme와 host를 다시 본다.
     *
     * @param location 응답의 Location 헤더. 상대 경로면 현재 주소를 기준으로 푼다.
     * @param current 지금 주소.
     * @param hopCount 이번이 몇 번째 redirect인지. 1부터 센다.
     */
    public URI requireAllowedRedirect(String location, URI current, int hopCount, SourceKind kind) {
        if (hopCount > config.maxRedirects()) {
            throw reject(TOO_MANY_REDIRECTS);
        }
        URI target;
        try {
            target = current.resolve(parseRelative(location));
        } catch (IllegalArgumentException e) {
            throw reject(MALFORMED_URL);
        }
        return requireAllowed(target, kind);
    }

    /** 응답 content-type을 검사한다. 허용 목록 밖이면 malformed로 본다. */
    public void requireAllowedContentType(String contentType, SourceKind kind) {
        if (contentType == null || contentType.isBlank()) {
            throw reject(UNSUPPORTED_CONTENT_TYPE);
        }
        // "application/rss+xml; charset=UTF-8"에서 매개변수를 떼고 비교한다.
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!allowedContentTypes(kind).contains(mediaType)) {
            throw reject(UNSUPPORTED_CONTENT_TYPE);
        }
    }

    public int maxRedirects() {
        return config.maxRedirects();
    }

    public long maxResponseBytes() {
        return config.maxResponseBytes();
    }

    public Duration connectTimeout() {
        return config.connectTimeout();
    }

    public Duration requestTimeout() {
        return config.requestTimeout();
    }

    /**
     * 외부 엔티티를 처리하지 않는 XML 파서를 만든다.
     *
     * <p>DTD와 외부 엔티티를 켜 두면 뉴스원이 준 XML이 서버의 파일을 읽어 제목에 실어 보낼 수 있다.
     */
    public XMLInputFactory secureXmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return factory;
    }

    /** 분류 코드만 담은 거부. 재시도해도 결과가 같으므로 retryable이 아니다. */
    public static NewsSourceClient.NewsSourceException reject(String errorCategory) {
        return new NewsSourceClient.NewsSourceException(errorCategory, false);
    }

    private URI requireAllowed(URI uri, SourceKind kind) {
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw reject(INSECURE_SCHEME);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject(MALFORMED_URL);
        }
        if (!isAllowedHost(host.toLowerCase(Locale.ROOT), kind)) {
            throw reject(HOST_NOT_ALLOWED);
        }
        return uri;
    }

    /** RSS는 등록된 매체 도메인만, GDELT는 전용 allowlist만 허용한다. */
    private boolean isAllowedHost(String host, SourceKind kind) {
        if (kind == SourceKind.RSS) {
            return publishers.findByHost(host).isPresent();
        }
        return config.gdeltHosts().stream()
                .map(allowed -> allowed.toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
    }

    private Set<String> allowedContentTypes(SourceKind kind) {
        return switch (kind) {
            case RSS -> lowercase(config.allowedRssContentTypes());
            case GDELT_DOC -> lowercase(config.allowedJsonContentTypes());
            case GDELT_FILE -> EXPORT_FILE_CONTENT_TYPES;
        };
    }

    private static Set<String> lowercase(Set<String> values) {
        return values.stream()
                .map(v -> v.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw reject(MALFORMED_URL);
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw reject(MALFORMED_URL);
        }
        if (!uri.isAbsolute()) {
            throw reject(MALFORMED_URL);
        }
        return uri;
    }

    private static URI parseRelative(String location) {
        if (location == null || location.isBlank()) {
            throw reject(MALFORMED_URL);
        }
        try {
            return new URI(location.trim());
        } catch (URISyntaxException e) {
            throw reject(MALFORMED_URL);
        }
    }
}
