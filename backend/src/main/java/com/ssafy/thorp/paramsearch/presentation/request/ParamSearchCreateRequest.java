package com.ssafy.thorp.paramsearch.presentation.request;

import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 탐색 실행 요청(contracts/param-search-api.md §2). 미리보기와 같은 필드에 {@code registryVersion}이 더 붙는다.
 *
 * <p><b>{@code registryVersion}이 필수다.</b> 미리보기 응답에서 받은 값을 그대로 되돌려 보낸다 — 그 사이 등록표가 바뀌었으면 409로 멈추고
 * 사용자가 바뀐 요약을 다시 확인한다(FR-028). 확인한 작업량과 다른 것을 실행하면 재현 기록이 거짓이 된다.
 */
public record ParamSearchCreateRequest(
        @NotNull @Valid ParamSearchPreviewRequest.Universe universe,
        @NotNull @Valid ParamSearchPreviewRequest.Strategy strategy,
        @Valid ParamSearchPreviewRequest.Run run,
        @Valid ParamSearchPreviewRequest.Portfolio portfolio,
        Map<String, Map<String, List<Object>>> space,
        @NotBlank @Size(max = 64) String registryVersion) {

    public CreateParamSearchCommand toCommand(Long requesterId) {
        return new CreateParamSearchCommand(
                requesterId,
                universe.instrumentIds(),
                strategy.strategyIds(),
                run == null ? null : run.start(),
                run == null ? null : run.end(),
                portfolio == null ? null : portfolio.initialCash(),
                space,
                registryVersion);
    }
}
