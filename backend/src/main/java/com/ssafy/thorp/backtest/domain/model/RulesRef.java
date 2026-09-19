package com.ssafy.thorp.backtest.domain.model;

/**
 * 등록표의 규칙 행 하나를 가리키는 이름표. 표기는 {@code 전략/버전}이다(예: {@code TARGET_RISK_OVERLAY/1}).
 *
 * <p>실행의 {@code versions.scenarioRules}와 작업 메시지의 {@code run.scenarioRules.version}이 이 표기를 쓴다.
 * 발행·재시도는 이 기록을 다시 읽어 오버레이 명세를 고르므로(spec 004 T029), 쓰는 곳과 읽는 곳이 각자 문자열을 잇고 자르면 한쪽 표기가 바뀐 날 옛 실행의
 * 버전을 읽지 못한다. 그래서 만들기와 풀기를 이 타입 한 곳에 둔다.
 */
public record RulesRef(String strategyId, String rulesVersion) {

    private static final char SEPARATOR = '/';

    public RulesRef {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId must not be blank");
        }
        if (strategyId.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException("strategyId must not contain '/': " + strategyId);
        }
        if (rulesVersion == null || rulesVersion.isBlank()) {
            throw new IllegalArgumentException("rulesVersion must not be blank");
        }
    }

    /**
     * 기록된 표기를 다시 읽는다.
     *
     * @throws IllegalArgumentException 표기가 {@code 전략/버전}이 아니다
     */
    public static RulesRef parse(String value) {
        int separator = value == null ? -1 : value.indexOf(SEPARATOR);
        if (separator < 1 || separator == value.length() - 1) {
            throw new IllegalArgumentException(
                    "rules reference must be STRATEGY/VERSION: " + value);
        }
        return new RulesRef(value.substring(0, separator), value.substring(separator + 1));
    }

    /** 기록에 남기는 표기. */
    public String value() {
        return strategyId + SEPARATOR + rulesVersion;
    }

    @Override
    public String toString() {
        return value();
    }
}
