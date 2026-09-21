package com.ssafy.thorp.regimelink.application.port;

import com.ssafy.thorp.regimelink.application.exception.RegimeLinkPublishFailedException;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;

/**
 * 사례 연결 작업 메시지를 워커에 전달하는 출력 포트.
 *
 * <p>구간을 잘라 담는 조립은 application이 끝내고 여기에는 완성된 본문만 넘긴다. 구현은 {@code infrastructure.messaging}에 두고
 * application 계층은 이 인터페이스만 안다.
 *
 * <p>발행 전 관문(원천 실행이 {@code COMPLETED}인지, 잘라낸 범위가 {@code baselineDate}~{@code lastAvailable}를 덮는지,
 * 종목 수·행 수 상한을 넘지 않는지, 체결 기록이 있는지)은 호출자가 이미 통과시킨 것으로 본다(계약 "검증 책임").
 */
public interface RegimeLinkJobPublisher {

    /**
     * {@code regime-link.jobs}로 작업 한 건을 발행한다.
     *
     * <p>브로커가 받았다고 확인(publisher confirm)한 뒤에만 정상 반환한다. 확인되지 않으면 {@link
     * RegimeLinkPublishFailedException}을 던지며, 호출자는 링크를 발행된 것으로 기록하지 않는다 — 발행 실패를 성공으로 숨기면 영원히
     * {@code QUEUED}인 링크가 남는다. 구현이 다른 예외 유형을 새로 만들지 않는 것이 이 포트의 계약이다.
     *
     * <p>메시지 속성({@code message_id = "RLJ-" + linkId + "-" + attempt}, {@code correlation_id =
     * linkId}, header {@code schemaVersion})은 구현이 {@code job}에서 뽑아 붙인다.
     */
    void publish(RegimeLinkJob job);
}
