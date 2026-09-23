package com.ssafy.thorp.news.application.port;

import com.ssafy.thorp.news.application.result.NewsFetchResult;

/**
 * 외부 뉴스원에서 기사 메타데이터를 가져오는 경계. 구현은 infrastructure에 둔다.
 *
 * <p>구현은 공통 HTTP 보안 경계를 반드시 통과한다. HTTPS와 등록된 host만 허용하고, redirect는 제한된 횟수만 hop마다 다시 검사하며, 연결·전체 제한
 * 시간과 응답 크기 상한, 허용 content-type을 적용하고 XML 외부 엔티티 처리를 비활성화한다(spec FR-020).
 *
 * <p>구현은 기사 본문을 읽거나 돌려주지 않는다(spec FR-002).
 *
 * <p>호출은 트랜잭션 밖에서 이뤄진다. 네트워크를 기다리는 동안 DB 커넥션을 잡고 있지 않는다(spec FR-022).
 */
public interface NewsSourceClient {

    /**
     * 한 RSS 채널을 조건부 요청으로 가져온다.
     *
     * @throws NewsSourceException 수집을 이번 회차에 마칠 수 없는 경우. 오류 분류만 담고 URL·인증 정보·응답 원문을 담지 않는다.
     */
    NewsFetchResult fetch(RssNewsSource source);

    /**
     * GDELT에서 제한된 구간과 주제로 가져온다.
     *
     * <p>결과 상한에 걸리면 {@link NewsFetchResult#resultCapReached()}가 참인 결과를 돌려준다. 구간을 나누어 다시 부르는 판단은
     * 호출부가 한다.
     */
    NewsFetchResult fetch(GdeltNewsSource source);

    /**
     * 수집 실패. 메시지에 분류 코드만 담는다.
     *
     * <p>{@code transient}가 참이면 제한된 재시도 뒤 다음 회차로 미룬다. 거짓이면 설정 오류이므로 재시도하지 않고 비활성화 후보로 기록한다(spec
     * FR-013).
     */
    class NewsSourceException extends RuntimeException {

        private final String errorCategory;
        private final boolean retryable;
        private final java.time.Duration retryAfter;

        public NewsSourceException(String errorCategory, boolean retryable) {
            this(errorCategory, retryable, null);
        }

        /**
         * 뉴스원이 {@code Retry-After}로 언제 다시 오라고 밝힌 경우.
         *
         * <p>이 값이 있으면 우리가 계산한 백오프보다 <b>먼저</b> 쓴다. 매체가 정한 간격을 우리 계산으로 덮으면 차단당할 수 있다(plan "수집과 재시도").
         */
        public NewsSourceException(
                String errorCategory, boolean retryable, java.time.Duration retryAfter) {
            super(errorCategory);
            this.errorCategory = errorCategory;
            this.retryable = retryable;
            this.retryAfter = retryAfter;
        }

        public String errorCategory() {
            return errorCategory;
        }

        public boolean isRetryable() {
            return retryable;
        }

        /** 뉴스원이 밝힌 재시도 간격. 밝히지 않았으면 빈 값이다. */
        public java.util.Optional<java.time.Duration> retryAfter() {
            return java.util.Optional.ofNullable(retryAfter);
        }
    }
}
