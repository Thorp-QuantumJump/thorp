package com.ssafy.thorp.news.application.exception;

/**
 * 뉴스 조회 요청이 계약을 벗어났다(contracts/openapi.yaml 400).
 *
 * <p>여기 해당하는 것은 사용자가 고쳐서 다시 부를 수 있는 것뿐이다. 한쪽만 준 기간, 역전된 기간, 상한을 넘는 기간, 001에 등록되지 않은 섹터 식별자다.
 *
 * <p><b>자료가 없는 것은 여기 들어오지 않는다.</b> 빈 결과·수집 지연·허가 대기는 오류가 아니라 정상 화면의 상태다(spec FR-014·FR-024). 이 둘을
 * 섞으면 외부 원천 장애가 클라이언트 오류로 보이고, 화면은 고칠 수 없는 것을 고치라고 말하게 된다.
 *
 * <p>메시지에 요청 값을 그대로 싣지 않는다. 주소나 질의 문자열이 응답에 새어 나가지 않게 하는 가림 규칙이 API 응답에도 그대로 적용된다(spec FR-021).
 */
public class NewsQueryException extends RuntimeException {

    public NewsQueryException(String message) {
        super(message);
    }
}
