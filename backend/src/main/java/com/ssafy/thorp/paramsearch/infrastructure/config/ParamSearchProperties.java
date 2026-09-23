package com.ssafy.thorp.paramsearch.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 자동 탐색 실행 한도. 접두는 {@code thorp.param-search}다.
 *
 * <p><b>네 값 모두 코드 기본값이 없고 {@code @NotNull}이다.</b> 후보 수 상한은 PD-06 실측 대기, 발행 창·실행시간 상한·연속 실패 한도는
 * PD-05 잔여로 팀 확정 대기다. constitution "AI 작업 방식"이 "기획서가 실행 전 결정으로 남긴 수치를 임의로 확정하지 않는다"를 요구하므로 코드가 값을
 * 정하지 않는다. 미설정이면 기동이 실패해 설정으로 드러난다 — 조용히 임의값으로 도는 것보다 낫다.
 *
 * <p>확정 전까지 이 값들은 {@link #provisionalNames()}를 통해 API 응답의 {@code limits.provisional}에 실리고, 화면이 임시값
 * 표시를 붙인다(FR-005a).
 *
 * <p>{@code dispatchWindow}가 "묶음이 한 번에 큐에 올려둔 미완료 후보 수"다(research R2). 접수 시점에 후보 전량을 발행하지 않는 이유는 두
 * 가지다 — 한 묶음이 {@code backtest.jobs}를 독점하지 않게 하고, 취소를 "다음 후보를 발행하지 않는다"는 동작으로 만들 수 있게 한다. 이미 큐에 든
 * 메시지는 회수할 수 없다.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "thorp.param-search")
public class ParamSearchProperties {

    /**
     * 확정되지 않은 한도 항목의 이름. API 응답의 {@code limits.provisional}에 그대로 실린다.
     *
     * <p>설정이 아니라 코드 상수다. 어떤 값이 확정되었는지는 팀 결정이므로 설정 토글로 바꿀 일이 아니고, 이 목록에서 이름을 지우는 변경은 리뷰를 거쳐야 한다.
     * PD-05·PD-06이 확정되면 해당 이름을 지운다.
     */
    private static final List<String> PROVISIONAL_NAMES =
            List.of(
                    "maxCandidates", // PD-06 실측 후 고정
                    "dispatchWindow", // PD-05 잔여
                    "timeLimitSeconds", // PD-05 잔여
                    "consecutiveFailureLimit"); // PD-05 잔여

    /** 묶음 하나가 만들 수 있는 유효 후보 수의 상한. 초과하면 접수를 거부하고 표본 추출·간격 확대를 하지 않는다(FR-005b). */
    @NotNull
    @Min(1)
    private Integer maxCandidates;

    /** 한 묶음이 동시에 큐에 올려두는 미완료 후보 수(발행 창). */
    @NotNull
    @Min(1)
    private Integer dispatchWindow;

    /** 묶음 시작 시각 기준 경과 시간 상한(초). 도달하면 발행을 멈추고 실행 중 후보는 완주시킨다. */
    @NotNull
    @Min(1)
    private Long timeLimitSeconds;

    /** 연속 실패 허용 횟수. 도달하면 발행을 멈춘다. */
    @NotNull
    @Min(1)
    private Integer consecutiveFailureLimit;

    /** 확정되지 않은 한도 항목의 이름. */
    public List<String> provisionalNames() {
        return PROVISIONAL_NAMES;
    }
}
