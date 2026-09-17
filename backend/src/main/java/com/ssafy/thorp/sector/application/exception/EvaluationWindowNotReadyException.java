package com.ssafy.thorp.sector.application.exception;

/**
 * 공통 평가 가능 기간을 산출할 재료가 아직 없다 (contracts/sector-api.md: 409 {@code EVALUATION_WINDOW_NOT_READY}).
 *
 * <p>세 가지가 여기로 온다. 열린 대응표를 대상으로 완전하게 수집된 자료 버전이 없을 때, 그 자료 버전에 거래일이 하나도 없을 때, 그리고 후보 가운데 상장일 이후의
 * 거래일이 달력에 아직 하나도 없는 종목이 있을 때다.
 *
 * <p><b>빈 기간이나 0을 만들어 내지 않는다</b>(원칙 VI). 셋 다 파이프라인이 자료를 적재하면 달라지는 상태라 재시도 가능한 409이며, 결과 조회가 {@code
 * RESULT_NOT_READY}에 409를 쓰는 것과 같은 판단이다.
 *
 * <p><b>평가 불가는 여기에 해당하지 않는다.</b> 후보를 워밍업만큼 밀 수 없거나 구간이 뒤집힌 것은 오류가 아니라 {@code evaluable = false}인 정상
 * 응답이고, 화면은 "이 조합은 분석할 수 없습니다" 한 문장을 보인다.
 */
public class EvaluationWindowNotReadyException extends RuntimeException {

    public EvaluationWindowNotReadyException(String message) {
        super(message);
    }
}
