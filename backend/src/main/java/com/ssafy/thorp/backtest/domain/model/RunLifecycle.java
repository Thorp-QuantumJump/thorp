package com.ssafy.thorp.backtest.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 실행이 언제 무슨 일을 겪었고 브로커에 몇 번 넘겼는지(spec 004 R-18).
 *
 * <p>{@code publishCount}는 <b>confirm 횟수가 아니라 발행 시도 횟수</b>다. 시도 직전에 올린다. confirm이 유실된 첫 발행도 브로커에는
 * 들어가 있을 수 있어서, confirm을 세면 실제 메시지 수를 과소평가하고 다른 실행이 살아 있는데도 DLQ만 보고 FAILED로 종결하게 된다. 시도 횟수는 실제 메시지
 * 수의 <b>상한</b>이라 그 방향의 오류가 없다.
 *
 * @param queuedAt 접수 시각. 대기 시간({@code queueWaitSeconds})의 기준
 * @param startedAt 첫 STARTED 수신 시각. null = 아직 시작하지 않음
 * @param finishedAt 종결 시각. null = 아직 끝나지 않음
 * @param publishedAt 발행 confirm 시각. null이면서 QUEUED면 재발행 대상이다
 * @param publishCount 발행 시도 횟수(상한). DLQ 종결 판정의 분모
 * @param dlqCount DLQ로 끝난 메시지 수. {@code dlqCount >= publishCount}일 때만 실행을 실패로 종결한다
 * @param cancelRequestedAt CANCEL 을 보낸 시각(R-04·R-19). null = 취소 대기 아님. 접수는 이 값이 있는 실행에 합류하지 않고, 유예
 *     감시는 이 시각부터 FINISHED 를 기다린다
 * @param cancelReason 그 CANCEL 을 보낸 이유. 무응답 강제 종결의 실패 사유가 이 값으로 갈린다
 * @param lastEventAt 워커가 살아 있다는 마지막 신호(STARTED·PROGRESS 수신 시각). 정체 판정 기준이다 — <b>발행 기록은 이 값을 건드리지
 *     않는다</b>. {@code updated_at} 으로 대신하면 발행 기록 때문에 멈춘 실행이 살아 있는 것처럼 보인다
 */
public record RunLifecycle(
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant publishedAt,
        int publishCount,
        int dlqCount,
        Instant cancelRequestedAt,
        CancelReason cancelReason,
        Instant lastEventAt) {

    public RunLifecycle {
        if (publishCount < 0) {
            throw new IllegalArgumentException(
                    "publishCount must not be negative: " + publishCount);
        }
        if (dlqCount < 0) {
            throw new IllegalArgumentException("dlqCount must not be negative: " + dlqCount);
        }
    }

    public static RunLifecycle queuedAt(Instant at) {
        return new RunLifecycle(
                Objects.requireNonNull(at, "queuedAt must not be null"),
                null,
                null,
                null,
                0,
                0,
                null,
                null,
                null);
    }

    /** 발행 시도 직전. confirm 여부와 무관하게 올린다. */
    public RunLifecycle publishAttempted() {
        return new RunLifecycle(
                queuedAt,
                startedAt,
                finishedAt,
                publishedAt,
                publishCount + 1,
                dlqCount,
                cancelRequestedAt,
                cancelReason,
                lastEventAt);
    }

    /** confirm 이 돌아온 뒤. 재발행기는 이 값이 채워진 실행을 건너뛴다. */
    public RunLifecycle published(Instant at) {
        return new RunLifecycle(
                queuedAt,
                startedAt,
                finishedAt,
                at,
                publishCount,
                dlqCount,
                cancelRequestedAt,
                cancelReason,
                lastEventAt);
    }

    public RunLifecycle deadLettered() {
        return new RunLifecycle(
                queuedAt,
                startedAt,
                finishedAt,
                publishedAt,
                publishCount,
                dlqCount + 1,
                cancelRequestedAt,
                cancelReason,
                lastEventAt);
    }

    /** 첫 STARTED 만 기록한다 — 재전달·중복 실행이 대기 시간을 뒤로 밀면 측정이 거짓말을 한다. */
    public RunLifecycle started(Instant at) {
        return startedAt != null
                ? this
                : new RunLifecycle(
                        queuedAt,
                        at,
                        finishedAt,
                        publishedAt,
                        publishCount,
                        dlqCount,
                        cancelRequestedAt,
                        cancelReason,
                        lastEventAt);
    }

    /**
     * 마지막 활성 요청이 취소해 CANCEL 을 보냈다(R-04). 이 값이 있으면 접수는 이 실행에 합류하지 않고(취소 대기), 유예 감시(T014)는 이 시각부터
     * FINISHED 를 기다린다.
     */
    public RunLifecycle cancelRequested(Instant at, CancelReason reason) {
        return new RunLifecycle(
                queuedAt,
                startedAt,
                finishedAt,
                publishedAt,
                publishCount,
                dlqCount,
                Objects.requireNonNull(at, "cancelRequestedAt must not be null"),
                Objects.requireNonNull(reason, "cancelReason must not be null"),
                lastEventAt);
    }

    /** 워커가 살아 있다는 신호를 받았다(STARTED·PROGRESS). 정체 판정의 기준점을 민다. */
    public RunLifecycle eventReceived(Instant at) {
        return new RunLifecycle(
                queuedAt,
                startedAt,
                finishedAt,
                publishedAt,
                publishCount,
                dlqCount,
                cancelRequestedAt,
                cancelReason,
                Objects.requireNonNull(at, "lastEventAt must not be null"));
    }

    /** 마지막 신호로부터 {@code silence} 넘게 조용한가. 신호가 한 번도 없으면 접수 시각을 기준으로 본다. */
    public boolean staleSince(Instant now, java.time.Duration silence) {
        Instant since = lastEventAt != null ? lastEventAt : queuedAt;
        return since != null && since.plus(silence).isBefore(now);
    }

    /** CANCEL 을 보낸 지 {@code grace} 가 지나도록 종결되지 않았는가. */
    public boolean cancelUnanswered(Instant now, java.time.Duration grace) {
        return cancelRequestedAt != null && cancelRequestedAt.plus(grace).isBefore(now);
    }

    public RunLifecycle finished(Instant at) {
        return new RunLifecycle(
                queuedAt,
                startedAt,
                at,
                publishedAt,
                publishCount,
                dlqCount,
                cancelRequestedAt,
                cancelReason,
                lastEventAt);
    }

    /** 발행한 메시지가 모두 DLQ 로 끝났는가. 하나라도 남아 있으면 그 실행의 결과를 기다린다(R-18 ③). */
    public boolean allMessagesDeadLettered() {
        return publishCount > 0 && dlqCount >= publishCount;
    }

    /** 자동 재시도(브로커 재전달)로 실행됐는가. 재발행으로 생긴 중복 실행은 재시도로 세지 않는다(R-06). */
    public boolean autoRetried(int attempt) {
        return attempt > publishCount;
    }
}
