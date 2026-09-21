package com.ssafy.thorp.paramsearch.application.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 탐색 묶음 하나가 접수됐다. <b>실행이 시작된 묶음에서만</b> 발행한다.
 *
 * <p>거절된 접수({@code REJECTED} 행: 후보 상한 초과·유효 조합 0건·전략 거부)는 발행하지 않는다. 계산이 시작되지 않아 다시 열 것이 없고, 목록에 두면
 * 사용자가 "돌다 만 탐색"으로 읽는다(spec 006 data-model §4).
 *
 * <p>007은 수신자를 알지 못한다 — 이벤트 타입만 공개하고 {@code myrecord}를 참조하지 않는다(spec 006 research R16).
 *
 * @param ownerUserId 접수 요청자. 007 접수는 로그인 사용자만 받으므로 비어 있지 않다
 * @param instrumentIds 탐색 대상 종목. 섹터 코드는 받는 쪽이 대응표로 옮긴다 — 007은 섹터를 다루지 않는다
 */
public record ParamSearchAccepted(
        long paramSearchId,
        Long ownerUserId,
        List<String> strategyIds,
        List<String> instrumentIds,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant acceptedAt) {

    public ParamSearchAccepted {
        strategyIds = List.copyOf(strategyIds);
        instrumentIds = List.copyOf(instrumentIds);
    }
}
