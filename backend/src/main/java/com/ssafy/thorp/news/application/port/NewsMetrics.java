package com.ssafy.thorp.news.application.port;

import java.time.Duration;

/**
 * 뉴스 도메인의 운영 지표 기록 경계(spec FR-023, plan "보관과 관측성").
 *
 * <p><b>label에는 가림 규칙을 통과한 값만 넣는다.</b> {@code sourceKey}, 출처 종류, 사전에 정의된 오류 분류 코드뿐이다. 주소·질의 문자열·응답
 * 원문·매체 접근 주소는 지표에도 실리지 않는다(spec FR-021·SC-009). 지표는 로그보다 오래 남고 외부 수집기로 나가므로 로그보다 더 조심해야 한다.
 *
 * <p>label 값의 가짓수가 늘어나는 값은 넣지 않는다. 기사 식별자나 URL을 label로 쓰면 시계열이 무한히 갈라진다.
 *
 * <p>포트로 두는 이유는 application 계층이 Micrometer를 직접 부르지 않게 하기 위함이다. 시험은 기록된 값을 확인할 대역을 끼운다.
 */
public interface NewsMetrics {

    /**
     * 한 수집 회차의 결과.
     *
     * @param sourceKey 뉴스원 식별자.
     * @param duration 회차 소요 시간.
     * @param collected 저장한 기사 수.
     * @param excluded 조건에 맞지 않아 제외한 수.
     * @param duplicates 이미 본 기사라 건너뛴 수.
     * @param digests 다시 만든 묶음 수.
     */
    void recordIngestRun(
            String sourceKey,
            Duration duration,
            int collected,
            int excluded,
            int duplicates,
            int digests);

    /**
     * 수집 실패.
     *
     * @param errorCategory 사전에 정의된 분류 코드. 자유 문자열을 넣지 않는다.
     */
    void recordIngestFailure(String sourceKey, String errorCategory);

    /** 회차 안에서 다시 시도한 횟수. */
    void recordRetry(String sourceKey);

    /**
     * 항목 하나를 제외한 사유.
     *
     * <p>제외를 한 숫자로만 세면 왜 버려졌는지 알 수 없다. 관련성 미달과 중복 열쇠 실패와 식별자 충돌은 대응이 서로 다르다 — 앞의 것은 등록표를 손봐야 하고, 뒤의
     * 것은 코드를 봐야 한다.
     *
     * @param reason 사전에 정의된 사유 코드. 자유 문자열을 넣지 않는다.
     */
    void recordExcluded(String sourceKey, String reason);

    /**
     * 뉴스원별 마지막 성공 이후 흐른 시간.
     *
     * <p>실패 건수만으로는 <b>조용히 멈춘 뉴스원</b>을 찾지 못한다. 회차 자체가 돌지 않으면 실패도 늘지 않기 때문이다. 이 값이 계속 커지는 것으로만 알 수
     * 있다.
     *
     * @param sinceLastSuccess 한 번도 성공하지 못했으면 빈 값.
     */
    void recordLastSuccessAge(String sourceKey, java.util.Optional<Duration> sinceLastSuccess);

    /**
     * 조회 한 건.
     *
     * @param state 응답 상태. READY·EMPTY·STALE·PREPARING 중 하나다.
     */
    void recordQuery(String state, Duration duration);

    /**
     * 보관 기한 일괄 삭제 회차의 결과(spec FR-019·FR-023).
     *
     * <p>지운 행 수를 표마다 남긴다. 합계만 남기면 어느 표가 지워지지 않았는지 알 수 없고, "예외로 보존한 표가 0개"라는 기준(SC-007)을 지표로 확인할 수
     * 없다.
     *
     * <p>지운 행이 0건인 회차도 기록한다. 기한 뒤에는 그것이 정상이고, 기록이 없으면 작업이 도는지 멈췄는지 구별되지 않는다.
     *
     * @param deletedRowsByTable 표 이름 → 지운 행 수. 표 이름은 코드에 고정된 값이라 label 가짓수가 늘지 않는다.
     */
    void recordRetentionRun(java.util.Map<String, Long> deletedRowsByTable);

    /** 제외 사유. 지표 label로 그대로 나가므로 대문자와 밑줄만 쓴다. */
    final class ExclusionReason {

        /** 등록된 섹터·주제에 연결되지 않았다. */
        public static final String NOT_RELEVANT = "NOT_RELEVANT";

        /** 같은 기사를 다시 알아볼 식별자를 만들 수 없다. */
        public static final String NO_DUPLICATE_KEY = "NO_DUPLICATE_KEY";

        /** 주소를 정규화할 수 없다. */
        public static final String MALFORMED_URL = "MALFORMED_URL";

        /** 기사를 낸 매체를 정할 수 없다. */
        public static final String NO_PUBLISHER = "NO_PUBLISHER";

        /** 외부 식별자가 다른 기사와 겹친다. */
        public static final String ID_COLLISION = "ID_COLLISION";

        private ExclusionReason() {}
    }
}
