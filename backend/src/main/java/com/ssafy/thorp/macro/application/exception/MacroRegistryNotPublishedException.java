package com.ssafy.thorp.macro.application.exception;

/**
 * 등록표가 아직 한 번도 발행되지 않았다 — {@code macro_current_publication} 에 행이 없다.
 *
 * <p><b>자료 없음과 다르다.</b> 자료 버전이 없는 것은 정상적인 조회 상태라 200 으로 17개 등록 정보와 빈 값을 내보내지만, 등록표 자체가 없으면 17개가
 * 무엇인지조차 말할 수 없어 내보낼 응답이 만들어지지 않는다. 그래서 이것만 장애로 다룬다.
 */
public class MacroRegistryNotPublishedException extends RuntimeException {

    public MacroRegistryNotPublishedException() {
        super("지표 등록표가 아직 발행되지 않았습니다. data/ 배치가 계산 등록표와 화면 분류를 먼저 적재해야 합니다");
    }
}
