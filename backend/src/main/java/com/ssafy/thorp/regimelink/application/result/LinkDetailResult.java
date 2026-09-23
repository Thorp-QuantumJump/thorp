package com.ssafy.thorp.regimelink.application.result;

import com.ssafy.thorp.regimelink.application.port.RunProgressView;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkResultRepository;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkId;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import java.time.Instant;

/**
 * 링크 하나의 상태와 구간 요약 (contracts §3).
 *
 * @param publicToken <b>요청자가 제시한 토큰 그대로</b>다. 다른 토큰은 싣지 않는다(research R14)
 * @param result 저장된 워커 산출물. 계약이 정한 JSON 을 그대로 보관한 것이라 조회가 수로 되돌리지 않는다 — 자릿수가 깎이지 않는다
 * @param failure 실패 사실. {@code retryable}은 실패 코드와 시도 번호로 그때그때 계산한다
 * @param runProgress {@code PENDING_RUN}일 때의 004 진행 상태. 그 밖에는 null
 */
public record LinkDetailResult(
        LinkId linkId,
        String publicToken,
        CaseRef caseRef,
        Scenario scenario,
        LinkStatus status,
        PostWindow postWindow,
        Availability availability,
        RunProgressView runProgress,
        Failure failure,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        RegimeLinkResultRepository.StoredResult result) {

    /**
     * @param retryable 화면이 '다시 시도'를 두는가. 거짓이면 같은 입력으로 다시 눌러도 같은 결과다
     */
    public record Failure(String code, String message, boolean retryable) {}
}
