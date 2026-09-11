package com.ssafy.thorp.paramsearch.presentation.request;

import com.ssafy.thorp.paramsearch.application.command.PreviewParamSearchCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 실행 전 확인 요청(contracts/param-search-api.md §1).
 *
 * <p><b>후보 숫자를 받지 않는다.</b> 사용자가 정하는 것은 전략과 섹터뿐이고 몇 건을 돌릴지는 등록표와 한도가 정한다(SC-001).
 *
 * <p>{@code space}는 전략 ID → (축 이름 → 후보 값 배열)로 <b>전략별 중첩</b>이다 — 전략마다 축 구성이 다르고 축 이름이 겹칠 수
 * 있다(FR-027). 생략하면 등록표 전체 범위를 쓴다.
 */
public record ParamSearchPreviewRequest(
        @NotNull @Valid Universe universe,
        @NotNull @Valid Strategy strategy,
        @Valid Run run,
        @Valid Portfolio portfolio,
        Map<String, Map<String, List<Object>>> space) {

    /** 종목 형식은 004 접수와 같다 — 여섯 자리 종목코드에 거래소 접미가 붙는다. */
    public record Universe(
            @NotEmpty @Size(max = 50)
                    List<@Pattern(regexp = "\\d{6}\\.XKRX", message = "종목 코드 형식이 올바르지 않습니다") String>
                            instrumentIds) {}

    public record Strategy(@NotEmpty List<@Size(max = 64) String> strategyIds) {}

    public record Run(LocalDate start, LocalDate end) {}

    public record Portfolio(@Positive BigDecimal initialCash) {}

    public PreviewParamSearchCommand toCommand(Long requesterId) {
        return new PreviewParamSearchCommand(
                requesterId,
                universe.instrumentIds(),
                strategy.strategyIds(),
                run == null ? null : run.start(),
                run == null ? null : run.end(),
                portfolio == null ? null : portfolio.initialCash(),
                space);
    }
}
