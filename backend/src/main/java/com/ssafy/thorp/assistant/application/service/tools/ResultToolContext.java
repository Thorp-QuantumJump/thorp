package com.ssafy.thorp.assistant.application.service.tools;

import com.ssafy.thorp.assistant.application.exception.ToolUnavailableException;
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 요청 시작 때 풀린 결과에 도구를 묶는 값들. 도구는 이 컨텍스트로 결과를 읽으므로 인자에 식별자가 없다(FR-007).
 *
 * @param token 공개 토큰. 서버 안에서만 쓰이고 근거·모델 어디에도 나가지 않는다
 * @param start 평가 시작일({@code effectiveStart}). 도구 인자는 이 구간 안이어야 한다
 * @param labels 종목 코드 → 섹터 이름. 라벨에 코드가 아니라 이름을 쓰기 위한 표(없으면 코드 원문)
 * @param maxDrawdown {@code summary.maxDrawdown}. 하락 구간 도구가 화면(005)과 같은 정의로 대조한다
 * @param tradesPageSize 거래 첫 페이지의 공개 상한({@code thorp.result.trades-page-size})
 */
public record ResultToolContext(
        PublicResultService publicResults,
        String token,
        LocalDate start,
        LocalDate end,
        Map<String, String> labels,
        BigDecimal maxDrawdown,
        int tradesPageSize) {

    public String label(String instrumentId) {
        String name = labels.get(instrumentId);
        return name == null || name.isBlank() ? instrumentId : name;
    }

    /** 결과 기간 밖이면 실행하지 않는다 — 없는 날짜의 값을 만들지 않는다(원칙 VI). */
    public LocalDate requireInPeriod(LocalDate date, String argument) {
        if (date.isBefore(start) || date.isAfter(end)) {
            throw new ToolUnavailableException(
                    argument + "가 결과 기간(" + start + "~" + end + ") 밖입니다");
        }
        return date;
    }

    public void requireOrdered(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ToolUnavailableException("from 이 to 보다 늦습니다");
        }
    }

    static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
