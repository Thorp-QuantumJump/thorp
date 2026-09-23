package com.ssafy.thorp.news.infrastructure.client;

import com.ssafy.thorp.news.infrastructure.client.NewsHttpSecurityPolicy.SourceKind;
import java.net.URI;

/**
 * 보안 경계를 통과한 외부 HTTP 호출 경계.
 *
 * <p>인터페이스로 둔 이유는 수집 클라이언트를 네트워크 없이 fixture로 검증하기 위해서다. 실제 구현은 {@link JdkNewsHttpClient}다.
 *
 * <p>호출은 트랜잭션 밖에서 이뤄진다(spec FR-022).
 */
@FunctionalInterface
public interface NewsHttpClient {

    /**
     * 한 번 요청한다. redirect 추적과 host·크기·시간 한도는 구현이 정책에 맡겨 처리한다.
     *
     * @param url 이미 정책을 통과한 주소.
     * @param kind 뉴스원 종류. 허용 host와 content-type이 갈린다.
     * @param etag 조건부 요청에 실을 ETag. 없으면 null.
     * @param lastModified 조건부 요청에 실을 Last-Modified. 없으면 null.
     */
    NewsHttpResponse get(URI url, SourceKind kind, String etag, String lastModified);
}
