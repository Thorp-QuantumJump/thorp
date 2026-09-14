package com.ssafy.thorp.paramsearch.application.result;

/**
 * 실패 후보 재시도 결과(contracts/param-search-api.md §5.1).
 *
 * @param retried 이번에 다시 발행한 후보 수
 * @param remainingFailed 아직 실패로 남은 후보 수. 발행 창이 좁아 이번에 다 보내지 못한 만큼이며, <b>자동으로 이어 보내지 않는다</b> — 반복
 *     재시도는 사용자가 다시 요청할 때만 일어난다(계약 §5.1)
 */
public record ParamSearchRetryResult(
        String searchId, String status, int retried, long remainingFailed) {}
