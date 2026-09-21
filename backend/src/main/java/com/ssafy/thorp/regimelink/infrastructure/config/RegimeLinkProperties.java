package com.ssafy.thorp.regimelink.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사례 연결 운영 설정 (spec 011 T080). {@code thorp.regimelink.*}
 *
 * <p>세 값 모두 {@code @NotNull}이라 하나라도 비면 기동에 실패한다 — 설정 누락으로 "상한 없이 발행"이나 "감시가 돌지 않음"이 조용히 성립하지 않게 하려는
 * 것이다({@code BacktestLimitProperties}와 같은 규약). prod 는 기본값 없는 환경변수를 참조해 공통 설정의 임시값을 물려받지 않는다.
 *
 * <p><b>확정 수치는 여기서 정하지 않는다.</b> 공통 설정에 들어 있는 값은 계약이 정한 구조(사후 구간 63거래일 + 기준일 한 행, 대응표 섹터 여덟)에서 나온
 * 임시값이고, 실측 뒤 조정한다.
 */
@Validated
@ConfigurationProperties(prefix = "thorp.regimelink")
public class RegimeLinkProperties {

    /** {@code PENDING_RUN} 링크의 입력 실행을 다시 보는 간격. */
    @NotNull private Duration pendingRunSweepInterval;

    /** 작업 메시지 {@code inputs.portfolioSeries}의 최대 날짜 행 수(계약 "크기 상한"). */
    @NotNull
    @Min(1)
    private Integer jobMaxRows;

    /** 작업 메시지 보유 스냅샷 한 건의 최대 종목 수. 대응표 섹터 수를 넘을 수 없다. */
    @NotNull
    @Min(1)
    private Integer jobMaxInstruments;

    public Duration getPendingRunSweepInterval() {
        return pendingRunSweepInterval;
    }

    public void setPendingRunSweepInterval(Duration pendingRunSweepInterval) {
        this.pendingRunSweepInterval = pendingRunSweepInterval;
    }

    public Integer getJobMaxRows() {
        return jobMaxRows;
    }

    public void setJobMaxRows(Integer jobMaxRows) {
        this.jobMaxRows = jobMaxRows;
    }

    public Integer getJobMaxInstruments() {
        return jobMaxInstruments;
    }

    public void setJobMaxInstruments(Integer jobMaxInstruments) {
        this.jobMaxInstruments = jobMaxInstruments;
    }
}
