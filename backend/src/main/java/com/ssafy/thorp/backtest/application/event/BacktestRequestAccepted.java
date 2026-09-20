package com.ssafy.thorp.backtest.application.event;

import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 분석 요청 하나가 접수됐다. 새 실행을 만들었을 때도, 같은 조건의 실행에 합류·재사용했을 때도 발행한다.
 *
 * <p><b>004는 수신자를 알지 못한다.</b> 이벤트 타입만 공개하고 {@code myrecord}를 참조하지 않는다 — 006이 접수 서비스를 직접 부르면
 * backtest → myrecord → backtest 순환 참조가 생긴다(spec 006 research R16). 수신자는
 * {@code @TransactionalEventListener(phase = BEFORE_COMMIT)}로 받아 접수와 같은 트랜잭션에 묶인다.
 *
 * <p>합류·재사용에서도 발행하는 이유: 그때도 <b>요청은 새로 생기고</b> 그 사용자에게는 자기 분석이다. 실행이 같으므로 006은 기존 항목을 찾아
 * 돌려준다(FR-009) — 새 항목을 만들지 않는 판단은 받는 쪽이 한다.
 *
 * @param requesterId 요청자. {@code null} = 비로그인 요청이라 기록에 남지 않는다
 * @param executionType 단건인지 탐색 후보인지. 후보가 만든 실행은 기록 항목이 되지 않고 묶음 항목이 대표한다
 * @param sectors 투자 대상 섹터와 그 목표 비중. 비중은 사용자가 정하는 전략에만 있고 나머지는 {@code null}이다 — 받는 쪽이 이름을 지을 때 대표
 *     섹터를 고르는 데 쓴다(006 FR-003)
 */
public record BacktestRequestAccepted(
        long requestId,
        long runId,
        Long requesterId,
        ExecutionType executionType,
        List<AcceptedSector> sectors,
        String strategyId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant acceptedAt) {

    public BacktestRequestAccepted {
        sectors = List.copyOf(sectors);
    }

    /** 섹터 하나와 요청이 실은 목표 비중. */
    public record AcceptedSector(String sectorCode, BigDecimal targetWeight) {}
}
