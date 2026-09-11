package com.ssafy.thorp.paramsearch.application.exception;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

/**
 * 미리보기 때 확인한 등록표 버전이 접수 시점과 다르다(FR-028, research R9). 409다.
 *
 * <p>확인한 작업량과 다른 것을 실행하면 재현 기록이 거짓이 된다. 화면은 미리보기를 다시 호출해 바뀐 요약을 보이고 확인을 받는다.
 */
public class RegistryVersionChangedException extends BusinessException {

    public RegistryVersionChangedException(String requested, String current) {
        super(
                ErrorCode.REGISTRY_VERSION_CHANGED,
                "확인하신 뒤 전략 등록표가 바뀌었습니다: 확인한 버전=" + requested + ", 현재 버전=" + current);
    }
}
