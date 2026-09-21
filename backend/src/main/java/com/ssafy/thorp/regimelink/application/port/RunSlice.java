package com.ssafy.thorp.regimelink.application.port;

import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;

/**
 * 004 저장 결과에서 잘라낸 구간 입력 한 벌 (계약 "구간 입력의 출처와 제약", T031).
 *
 * <p><b>자르는 것은 어댑터이고 판정하는 것은 조립기다.</b> 004 결과의 모양(일별 스냅샷 안에 보유·현금흐름이 같이 들어 있다)을 011 메시지의 모양으로 옮기는
 * 일은 004를 아는 쪽이 하고, "발행해도 되는가"는 application 이 정한다.
 *
 * @param fillsRecorded 그 실행이 개별 체결을 남겼는가(004 {@code backtest_result.fills_recorded}). 거짓이면 구간 비용을
 *     세울 수 없으므로 발행하지 않고 재계산 확인을 받는다(T092)
 * @param analyticsMethod 원천 실행에 고정된 지표 산출 방식 버전. 004 {@code versions.engine}이다 — 그 값이 없는 실행은 체결도
 *     남기지 않았으므로 여기 오지 않는다
 */
public record RunSlice(
        RegimeLinkJob.RunSpec run,
        RegimeLinkJob.UniverseSpec universe,
        RegimeLinkJob.MarketDataSpec marketData,
        RegimeLinkJob.InputsSpec inputs,
        boolean fillsRecorded,
        String analyticsMethod) {}
