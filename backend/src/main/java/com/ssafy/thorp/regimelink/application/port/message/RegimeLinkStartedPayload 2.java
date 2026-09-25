package com.ssafy.thorp.regimelink.application.port.message;

import java.time.Instant;

/**
 * {@code STARTED} 이벤트 payload. 계산을 집어 들었다는 신호이며 링크를 {@code RUNNING}으로 옮긴다.
 *
 * @param resource 어느 프로세스가 집었는지. 재전달 진단용이라 못 채우면 키가 없다
 */
public record RegimeLinkStartedPayload(Instant startedAt, ProcessInfo resource) {

    /** 못 재는 플랫폼도 있어 두 값 모두 생략 가능하다. */
    public record ProcessInfo(Integer pid, String hostname) {}
}
