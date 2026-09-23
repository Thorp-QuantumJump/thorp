package com.ssafy.thorp.strategy.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 등록 정보 또는 실행 지원 목록 조회 실패(FR-028, research R9). 503이다.
 *
 * <p><b>대체값으로 실행하지 않는다.</b> 캐시된 등록 정보나 007 내부 기본값으로 채우면 그 묶음의 참조 버전이 뜻을 잃는다. 일시적 인프라 장애이므로 묶음을
 * {@code REJECTED}로 저장하지도 않는다 — {@code BROKER_UNAVAILABLE}을 묶음으로 남기지 않는 것과 같은 기준이다.
 */
public class RegistryUnavailableException extends BusinessException {

    public RegistryUnavailableException(String detail, Throwable cause) {
        super(ErrorCode.REGISTRY_UNAVAILABLE, "전략 등록 정보를 읽지 못했습니다: " + detail, cause);
    }
}
