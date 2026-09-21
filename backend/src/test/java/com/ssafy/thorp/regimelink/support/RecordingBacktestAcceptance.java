package com.ssafy.thorp.regimelink.support;

import com.ssafy.thorp.regimelink.application.port.BacktestAcceptancePort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 004 접수의 시험용 구현. <b>몇 번 어떤 본문으로 불렸는가</b>를 그대로 모아 둔다.
 *
 * <p>실제 004 접수를 부르려면 전략 규칙·비용 등록표까지 맞춘 픽스처가 필요하다. 여기서 보려는 것은 011 이 무엇을 접수하느냐(기간·시나리오·요청자)이지 004 가
 * 무엇을 계산하느냐가 아니므로, 접수는 가짜로 두고 호출 기록만 본다.
 *
 * <p>실행 식별자는 고정이고 요청 식별자는 호출마다 늘어난다 — 004 가 같은 조건의 실행을 재사용해도 호출자마다 새 요청을 만든다는 규칙을 그대로 흉내 낸 것이다.
 */
public class RecordingBacktestAcceptance implements BacktestAcceptancePort {

    /** 접수 한 건의 기록. 어느 경로로 어떤 기간을 접수했는지 그대로 남긴다. */
    public record Call(
            Kind kind,
            Long originRunId,
            long requesterId,
            LocalDate start,
            LocalDate d0,
            LocalDate end) {}

    public enum Kind {
        FULL_PERIOD,
        FRESH_CASH,
        RETRY
    }

    private final List<Call> calls = new ArrayList<>();
    private final Long runId;
    private final boolean completed;
    private long nextRequestId = 7_001L;

    public RecordingBacktestAcceptance(Long runId, boolean completed) {
        this.runId = runId;
        this.completed = completed;
    }

    @Override
    public AcceptedRun acceptFullPeriod(
            Long originRunId, long requesterId, LocalDate start, LocalDate end) {
        calls.add(new Call(Kind.FULL_PERIOD, originRunId, requesterId, start, null, end));
        return accepted();
    }

    @Override
    public AcceptedRun acceptFreshCash(
            Long originRunId, long requesterId, LocalDate start, LocalDate d0, LocalDate end) {
        calls.add(new Call(Kind.FRESH_CASH, originRunId, requesterId, start, d0, end));
        return accepted();
    }

    @Override
    public AcceptedRun retry(Long runId, Long requestId, long requesterId) {
        calls.add(new Call(Kind.RETRY, runId, requesterId, null, null, null));
        return accepted();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call only() {
        if (calls.size() != 1) {
            throw new IllegalStateException("접수가 하나가 아닙니다: " + calls.size());
        }
        return calls.get(0);
    }

    private AcceptedRun accepted() {
        return new AcceptedRun(runId, nextRequestId++, completed);
    }
}
