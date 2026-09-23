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
 * <p><b>세 값은 코드 기본값이 없고 {@code @NotNull}이다.</b> 후보 수 상한은 PD-06 실측 대기, 발행 창·연속 실패 한도는 PD-05 잔여로 팀 확정
 * 대기다. constitution "AI 작업 방식"이 "기획서가 실행 전 결정으로 남긴 수치를 임의로 확정하지 않는다"를 요구하므로 코드가 값을 정하지 않는다. 미설정이면
 * 기동이 실패해 설정으로 드러난다 — 조용히 임의값으로 도는 것보다 낫다.
 *
 * <p><b>{@code timeLimitSeconds}만 비울 수 있다</b> — 비우면 묶음 실행시간 상한을 두지 않는다. 묶음은 후보를 다 돌거나 연속 실패 한도·취소로만
 * 끝나고, 묶음 크기는 {@code maxCandidates}가 계속 막는다. 여기만 {@code @NotNull}이 아닌 이유는 "값이 빠졌다"와 "상한을 두지 않기로
 * 했다"를 구분해야 해서다 — 나머지 셋은 빠지면 기동이 실패하는 쪽이 맞다.
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
                    "timeLimitSeconds", // PD-05 잔여. 상한을 두지 않으면 목록에서 빠진다
                    "consecutiveFailureLimit"); // PD-05 잔여

    /** 묶음 하나가 만들 수 있는 유효 후보 수의 상한. 초과하면 접수를 거부하고 표본 추출·간격 확대를 하지 않는다(FR-005b). */
    @NotNull
    @Min(1)
    private Integer maxCandidates;

    /** 한 묶음이 동시에 큐에 올려두는 미완료 후보 수(발행 창). */
    @NotNull
    @Min(1)
    private Integer dispatchWindow;

    /**
     * 묶음 시작 시각 기준 경과 시간 상한(초). 도달하면 발행을 멈추고 실행 중 후보는 완주시킨다.
     *
     * <p><b>비우면 상한을 두지 않는다.</b> 0 이하는 설정 실수로 보고 기동을 막는다 — "상한 없음"은 값을 비워서 말한다.
     */
    @Min(1)
    private Long timeLimitSeconds;

    /** 연속 실패 허용 횟수. 도달하면 발행을 멈춘다. */
    @NotNull
    @Min(1)
    private Integer consecutiveFailureLimit;

    /**
     * 확정되지 않은 한도 항목의 이름.
     *
     * <p>실행시간 상한을 두지 않으면 그 이름은 빼고 준다 — 없는 한도에 "임시값"을 붙이면 화면이 있지도 않은 제한을 말하게 된다.
     */
    public List<String> provisionalNames() {
        if (timeLimitSeconds != null) {
            return PROVISIONAL_NAMES;
        }
        return PROVISIONAL_NAMES.stream().filter(name -> !"timeLimitSeconds".equals(name)).toList();
    }
}
