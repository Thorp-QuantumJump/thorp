package com.ssafy.thorp.regimelink.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code FINISHED} 이벤트 payload. 계산이 실패해도 <b>정상 결과</b>로 발행되므로 이 이벤트가 왔다는 것은 워커가 끝까지 처리했다는 뜻이다.
 *
 * <p>{@code COMPLETED}면 {@code result}가 있고 {@code error}가 없다. {@code FAILED}면 반대다. 둘 다 실린 payload는
 * 계약 위반이며 백엔드가 이벤트를 반영하기 전에 막는다.
 *
 * @param partial 구간 안 상장폐지로 63거래일을 채우지 못했다. {@code window21}은 언제나 완성이라 이 값과 무관하다
 * @param delistedOn {@code partial=true}일 때의 상장폐지일. 그 밖에는 키가 없다
 */
public record RegimeLinkFinishedPayload(
        RegimeLinkStatus status,
        boolean partial,
        LocalDate delistedOn,
        RegimeLinkResultPayload result,
        ErrorDetail error,
        ResourceUsage resource) {

    /**
     * @param message 사람이 읽는 진단 문구. 화면에 그대로 내보내지 않는다
     */
    public record ErrorDetail(RegimeLinkErrorCode code, String message) {}

    /** 측정 기록용. 백테스트와 달리 메모리·CPU는 재지 않는다 — 63거래일 계산이라 벽시계만으로 충분하다. */
    public record ResourceUsage(BigDecimal wallSeconds) {}
}
