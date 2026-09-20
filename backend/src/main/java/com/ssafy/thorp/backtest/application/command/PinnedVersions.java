package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.domain.model.RulesRef;

/**
 * 버전이 이미 고정된 요청을 접수할 때 함께 넘기는 것(spec 004 T029).
 *
 * <p>새 요청은 지금 등록된 규칙·등록표 버전만 받는다. 그런데 버전이 접수 전에 이미 정해진 요청이 둘 있다. 하나는 <b>실패·취소한 요청의 재시도</b>(T010)로,
 * 원본이 접수 때 판정받은 버전을 그대로 쓴다. 다른 하나는 <b>007 자동 탐색 묶음의 후보</b>로, 묶음을 접수할 때 규칙·등록표 버전을 스냅샷에 고정하고 후보를 나중에
 * 하나씩 넣는다(007 FR-012 "묶음 안 같은 규칙"). 둘을 새 요청으로 판정하면 규칙이 새 버전으로 대체된 날부터 옛 요청을 다시 돌릴 수 없고, 진행 중이던 묶음은
 * 남은 후보를 넣지 못해 멈춘다.
 *
 * @param retryOfRequestId 재시도면 원본 요청의 id(새 요청 행에 남는다), 묶음 후보면 null
 * @param scenarioRules 원본 실행이 쓴 시나리오 오버레이 규칙. 요청은 오버레이 버전을 싣지 않고 접수가 고르므로 원본의 기록을 넘겨받는다. 오버레이가 없으면
 *     null
 */
public record PinnedVersions(Long retryOfRequestId, RulesRef scenarioRules) {

    /** 실패·취소한 요청의 재시도. */
    public static PinnedVersions retryOf(long requestId, RulesRef scenarioRules) {
        return new PinnedVersions(requestId, scenarioRules);
    }

    /** 007 자동 탐색 묶음의 후보. 묶음은 시나리오 오버레이를 쓰지 않는다. */
    public static PinnedVersions searchBundle() {
        return new PinnedVersions(null, null);
    }
}
