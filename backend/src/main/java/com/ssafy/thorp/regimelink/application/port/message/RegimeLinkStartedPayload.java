package com.ssafy.thorp.regimelink.application.port.message;

import java.time.Instant;

/**
 * {@code STARTED} 이벤트 payload. 계산을 집어 들었다는 신호이며 링크를 {@code RUNNING}으로 옮긴다.
 *
 * @param resource 어느 프로세스가 집었는지(재전달 진단용). 계약상 필수이며 워커가 언제나 채운다(T106). 진단 값이라 받는 쪽은 없어도 실패하지 않는다
 */
public record RegimeLinkStartedPayload(Instant startedAt, ProcessInfo resource) {

    /** 계약상 두 값 모두 필수다(T106). */
    public record ProcessInfo(Integer pid, String hostname) {}
}
