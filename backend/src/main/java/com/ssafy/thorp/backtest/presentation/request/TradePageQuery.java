package com.ssafy.thorp.backtest.presentation.request;

import com.ssafy.thorp.backtest.application.port.message.TradeStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * {@code GET /backtests/{backtestId}/trades}의 질의 파라미터. 계약: {@code
 * docs/specs/backtest-api/README.md} §2.3.
 *
 * <p><b>{@code Integer}인 이유:</b> primitive {@code int}로 받으면 <b>생략과 명시적 {@code size=0}을 구분할 수
 * 없다</b>. {@code @ModelAttribute} record에는 {@code defaultValue} 기제가 없어 생략도 {@code 0}으로 바인딩되기 때문이다.
 * 계약은 생략을 100으로, {@code 0}을 400으로 규정한다.
 *
 * <p><b>DTO를 두고 {@code @RequestParam @Min}을 쓰지 않는 이유:</b> 그 경로는 구성에 따라 예외 종류가 갈린다 — 내장 메서드 검증이면
 * {@code HandlerMethodValidationException}이지만 클래스에 {@code @Validated}가 붙으면 AOP 검증이라 {@code
 * ConstraintViolationException}이 나고, 그건 MVC 표준 예외가 아니라 500이 된다. {@code @Valid @ModelAttribute}는 요청
 * DTO 검증의 기존 경로를 그대로 타므로 그 분기를 따질 필요가 없다.
 *
 * <p>기본값 적용과 {@code instrumentId} 형식 검증, {@code from ≤ to} 확인은 컨트롤러가 한다({@code
 * FindBacktestTradesQuery}로 변환하면서).
 *
 * <p>{@code from}·{@code to}(spec 005 T015)는 진입 또는 청산이 그 기간(양 끝 포함) 안에 있는 거래만 남긴다. 둘 중 하나만 보내도 된다.
 */
public record TradePageQuery(
        @Min(0) Integer page,
        @Min(1) @Max(1000) Integer size,
        String instrumentId,
        TradeStatus status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 100;

    public int pageOrDefault() {
        return page == null ? DEFAULT_PAGE : page;
    }

    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }
}
