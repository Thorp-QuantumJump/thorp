package com.ssafy.thorp.backtest.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 실행 한도 설정(spec 004 T014, research R-19). {@code thorp.backtest.limits.*}
 *
 * <p><b>다섯 값이 모두 {@code @NotNull} 이다.</b> 하나라도 비면 애플리케이션이 기동에 실패한다 — FR-006 "상한을 정하기 전에 무제한 실행을
 * 허용하지 않는다"를 설정 누락으로 우회할 수 없게 하려는 것이다. 운영에 값 없이 올라가는 것이 가장 나쁜 결과다.
 *
 * <p><b>값 자체는 이 Task 의 것이 아니다.</b> PD-06 이 실측(T012 집계 뷰) 뒤에 정한다. 그때까지 들어 있는 수는 임시값이고, 임시값으로 도는 동안
 * 응답에 {@code provisional} 로 그 사실을 표시한다 — 사용자가 이 상한을 확정된 서비스 정책으로 오해하면 안 된다.
 *
 * <p>동시 실행 상한은 여기 없다. 접수 시점 검사로는 보장되지 않고(그때 실행 중인 것이 없으면 모두 통과한 뒤 워커가 동시에 소비한다), 실제 상한은 워커 replica
 * × prefetch(1)라 배치 값이 곧 상한이다(R-19, T002 문서).
 */
@Validated
@ConfigurationProperties(prefix = "thorp.backtest.limits")
public class BacktestLimitProperties {

    /** 한 사용자가 대기시켜 둘 수 있는 <b>단건</b> 실행 수. 탐색 묶음은 007 의 발행 창이 통제하므로 세지 않는다. */
    @NotNull
    @Min(1)
    private Integer maxQueuedPerUser;

    /** 서버 전체의 대기 실행 수. 종류를 가리지 않는다 — 이것은 서버 보호다. */
    @NotNull
    @Min(1)
    private Integer maxQueuedTotal;

    /** 실행 하나가 돌 수 있는 최대 시간(초). 넘으면 CANCEL 을 보내 완료 범위와 함께 멈춘다. */
    @NotNull
    @Min(1)
    private Long maxRunSeconds;

    /** 마지막 이벤트로부터 이만큼 조용하면 정체로 본다(초). 정상 PROGRESS 간격의 몇 배로 잡는다. */
    @NotNull
    @Min(1)
    private Long staleRunSeconds;

    /** CANCEL 을 보낸 뒤 FINISHED 를 기다리는 시간(초). 넘으면 실행 주체가 없다고 보고 강제 종결한다. */
    @NotNull
    @Min(1)
    private Long cancelGraceSeconds;

    /** 이 값들이 아직 실측으로 확정되지 않았는가(PD-06). true 면 응답에 그 사실이 실린다. */
    private boolean provisional = true;

    public Integer getMaxQueuedPerUser() {
        return maxQueuedPerUser;
    }

    public void setMaxQueuedPerUser(Integer maxQueuedPerUser) {
        this.maxQueuedPerUser = maxQueuedPerUser;
    }

    public Integer getMaxQueuedTotal() {
        return maxQueuedTotal;
    }

    public void setMaxQueuedTotal(Integer maxQueuedTotal) {
        this.maxQueuedTotal = maxQueuedTotal;
    }

    public Long getMaxRunSeconds() {
        return maxRunSeconds;
    }

    public void setMaxRunSeconds(Long maxRunSeconds) {
        this.maxRunSeconds = maxRunSeconds;
    }

    public Long getStaleRunSeconds() {
        return staleRunSeconds;
    }

    public void setStaleRunSeconds(Long staleRunSeconds) {
        this.staleRunSeconds = staleRunSeconds;
    }

    public Long getCancelGraceSeconds() {
        return cancelGraceSeconds;
    }

    public void setCancelGraceSeconds(Long cancelGraceSeconds) {
        this.cancelGraceSeconds = cancelGraceSeconds;
    }

    public boolean isProvisional() {
        return provisional;
    }

    public void setProvisional(boolean provisional) {
        this.provisional = provisional;
    }
}
