package com.ssafy.thorp.paramsearch.application.port;

import java.util.List;

/**
 * 묶음에 적용할 실행 한도. 설정에서 읽은 값을 application 계층이 쓰는 모양으로 옮긴 것이다.
 *
 * <p><b>{@code ParamSearchProperties}를 서비스가 직접 주입받지 않는 이유</b>: 그 클래스는 {@code
 * infrastructure.config}에 있고 application은 infrastructure를 참조할 수 없다({@code ArchitectureTest} 계층 규칙).
 * 설정 형식이 바뀌어도 유스케이스가 흔들리지 않게 하는 경계이기도 하다.
 *
 * <p>나머지 값은 <b>팀 확정 전 임시값</b>이다(PD-05·PD-06). {@code provisional}이 그 사실을 응답까지 나른다(FR-005a).
 *
 * @param maxCandidates 유효 후보 수 상한. 넘으면 접수를 거부하고 표본 추출·간격 확대를 하지 않는다(FR-005b)
 * @param dispatchWindow 한 묶음이 동시에 큐에 올려두는 미완료 후보 수(research R2)
 * @param timeLimitSeconds 묶음 시작 시각 기준 경과 시간 상한. <b>{@code null}이면 상한을 두지 않는다</b> — 묶음은 후보를 다 돌거나 연속
 *     실패 한도·취소로만 끝난다. 후보 수 상한({@code maxCandidates})이 묶음 크기를 여전히 막는다
 * @param consecutiveFailureLimit 연속 실패 허용 횟수
 * @param provisional 임시값인 항목 이름
 */
public record SearchLimits(
        int maxCandidates,
        int dispatchWindow,
        Long timeLimitSeconds,
        int consecutiveFailureLimit,
        List<String> provisional) {

    public SearchLimits {
        if (maxCandidates < 1 || dispatchWindow < 1 || consecutiveFailureLimit < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
        // null 은 "상한 없음"이고 0 이하는 설정 실수다 — 둘을 같은 값으로 뭉개면 실수가 조용히 무제한이 된다.
        if (timeLimitSeconds != null && timeLimitSeconds < 1) {
            throw new IllegalArgumentException("timeLimitSeconds must be positive or absent");
        }
        provisional = List.copyOf(provisional);
    }

    /** 실행시간 상한을 두는가. 두지 않으면 {@code TimeLimitGuard}가 아무것도 막지 않는다. */
    public boolean hasTimeLimit() {
        return timeLimitSeconds != null;
    }
}
