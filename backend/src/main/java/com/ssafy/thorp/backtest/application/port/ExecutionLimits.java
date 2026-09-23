package com.ssafy.thorp.backtest.application.port;

import java.time.Duration;

/**
 * 실행 한도 값(spec 004 R-19). 설정에서 읽어 온 불변 사본이다.
 *
 * @param maxQueuedAnonymous 비로그인 요청이 만든 단건 대기 실행의 합계 상한(spec 005 B01). 비로그인은 사람을 가릴 수 없어 사용자별 한도 대신
 *     이 몫을 함께 나눠 쓴다 — 없으면 비로그인 클라이언트 하나가 전체 한도를 채워 로그인 사용자까지 막는다
 * @param provisional 아직 실측으로 확정되지 않은 임시값인가(PD-06). 응답에 실어 사용자가 확정 정책으로 오해하지 않게 한다
 */
public record ExecutionLimits(
        int maxQueuedPerUser,
        int maxQueuedTotal,
        int maxQueuedAnonymous,
        Duration maxRun,
        Duration staleRun,
        Duration cancelGrace,
        boolean provisional) {

    public ExecutionLimits {
        if (maxQueuedPerUser < 1 || maxQueuedTotal < 1 || maxQueuedAnonymous < 1) {
            throw new IllegalArgumentException("queue limits must be positive");
        }
        if (maxRun.isNegative() || maxRun.isZero()) {
            throw new IllegalArgumentException("maxRun must be positive");
        }
        if (staleRun.isNegative() || staleRun.isZero()) {
            throw new IllegalArgumentException("staleRun must be positive");
        }
        if (cancelGrace.isNegative() || cancelGrace.isZero()) {
            throw new IllegalArgumentException("cancelGrace must be positive");
        }
    }
}
