package com.ssafy.thorp.regimelink.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 사례 연결 작업 메시지 발행 실패. 브로커 장애는 도메인 규칙 위반이 아니라 유스케이스 수행 실패라 application 계층에 둔다.
 *
 * <p>{@code RegimeLinkJobPublisher.publish}가 브로커 확인을 받지 못했을 때 던지는 <b>유일한</b> 예외다. 호출자가 무엇을 잡아야 하는지
 * 계약에 이름으로 박아 두는 것이 목적이다 — 이름이 없으면 발행 실패가 아무도 잡지 않은 채 접수 경로 밖으로 번진다.
 *
 * <p>링크를 발행된 것으로 기록하기 전에 던져 그 저장을 되돌린다. 큐에 들어가지 못한 링크가 {@code QUEUED}로 남지 않게 하는 것이 목적이다.
 */
public class RegimeLinkPublishFailedException extends BusinessException {

    public RegimeLinkPublishFailedException(String linkId, Throwable cause) {
        super(ErrorCode.BROKER_UNAVAILABLE, "사례 연결 계산 작업을 큐에 넣지 못했습니다: linkId=" + linkId, cause);
    }
}
