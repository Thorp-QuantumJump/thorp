package com.ssafy.thorp.paramsearch.application.port;

/**
 * 접수하기 전에 이 사용자에게 묶음을 저장해 둘 자리가 있는지 묻는다(spec 006 FR-018, research R11).
 *
 * <p><b>규칙은 006이 소유하고 구현 자리는 007의 접수 경로다.</b> 007은 무엇을 세는지도, 상한이 얼마인지도 알지 못한다 — 여기서 아무 예외도 오지 않으면
 * 접수를 계속한다.
 *
 * <p><b>007이 006을 직접 부르지 않는 이유</b>: 006의 등록기가 이미 {@code ParamSearchAccepted}를 듣는다. 접수 서비스가 006 서비스를
 * 마주 부르면 {@code paramsearch ↔ myrecord} 양방향이 되고, 이 저장소는 같은 모양을 {@code strategy}·{@code
 * result}·{@code learning}에 대해 규칙으로 막아 두었다({@code ArchitectureTest} 규칙 8·9·10). 포트를 007이 소유하면 화살표는
 * {@code myrecord → paramsearch} 하나로 남는다.
 *
 * <p>구현이 하나뿐인 인터페이스지만 그 하나가 <b>다른 도메인에 있다</b>는 것이 이 자리의 값이다.
 */
public interface SaveSlotGuard {

    /**
     * 자리가 없으면 거절한다. 어떤 행이 쓰이기도, 어떤 후보가 발행되기도 전에 부른다 — 계산이 끝난 뒤 막으면 이미 쓴 자원이 버려진다.
     *
     * @param ownerId 접수 요청자. 비로그인 접수는 기록을 만들지 않으므로 호출자가 걸러 낸다
     * @throws com.ssafy.thorp.common.exception.BusinessException 자리가 없으면 {@code 409
     *     RECORD_LIMIT_REACHED}. 본문에 현재 개수와 상한이 실린다
     */
    void requireSaveSlot(long ownerId);
}
