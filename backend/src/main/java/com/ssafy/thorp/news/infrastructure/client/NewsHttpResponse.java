package com.ssafy.thorp.news.infrastructure.client;

/**
 * 외부 뉴스원 응답. 보안 경계를 통과한 뒤의 값만 담는다.
 *
 * <p>{@code body}는 크기 한도 안에서 읽은 바이트다. 원문을 로그·지표·오류 기록에 남기지 않는다(spec FR-021).
 *
 * @param statusCode HTTP 상태 코드.
 * @param contentType 응답 content-type. 304에는 없을 수 있다.
 * @param body 응답 본문. 304이면 비어 있다.
 * @param etag 다음 조건부 요청에 쓸 ETag.
 * @param lastModified 다음 조건부 요청에 쓸 Last-Modified.
 */
public record NewsHttpResponse(
        int statusCode, String contentType, byte[] body, String etag, String lastModified) {

    /** 변경 없음 응답인지 여부. RSS 조건부 요청에서만 나온다(spec FR-013). */
    public boolean isNotModified() {
        return statusCode == 304;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
