package com.ssafy.thorp.result.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 결과 공개 열람 설정(spec 005 T004). {@code thorp.result.*}
 *
 * <p><b>네 값이 모두 {@code @NotNull} 이다.</b> 하나라도 비면 기동에 실패한다 — 토큰 유효 시간이 없으면 비로그인 결과가 영영 열려 있게 되고, 거래
 * 내역 페이지 크기가 없으면 공개 경로에 상한 없는 조회가 열린다(원칙 IX). 운영은 {@code application-prod.yml}이 환경변수를 기본값 없이
 * 참조한다({@code BacktestLimitProperties}와 같은 방식).
 *
 * <p><b>값은 이 Task 의 것이 아니다.</b> 유효 시간은 PD-23, 거래 내역 페이지 크기는 R-11 이 정한다. 그때까지의 수는 설정 파일에만 있고 문서에 적지
 * 않는다.
 *
 * <p>{@code public-token-ttl}은 {@code backtest} 접수가 {@code @Value}로 읽어 만료 시각을 정한다. 그 쪽이 이 클래스를 주입받지
 * 않는 이유는 의존 방향이 {@code result → backtest} 하나이기 때문이다(ArchUnit 규칙 9). 같은 키를 여기서 {@code @NotNull}로 묶어
 * 두어 비어 있으면 기동 단계에서 막힌다.
 */
@Validated
@ConfigurationProperties(prefix = "thorp.result")
public class ResultProperties {

    /** 비로그인 요청의 공개 토큰 유효 시간. 기준은 요청 생성 시각이다(R-03, PD-23 확정 전 임시 기준). */
    @NotNull private Duration publicTokenTtl;

    /** 만료된 비로그인 요청을 훑는 주기. */
    @NotNull private Duration expirySweepInterval;

    /** 정리 작업이 한 번에 지우는 요청 수. */
    @NotNull
    @Min(1)
    private Integer expirySweepBatchSize;

    /** 공개 경로 거래 내역의 페이지 크기이자 상한(R-11 임시 상한). */
    @NotNull
    @Min(1)
    private Integer tradesPageSize;

    public Duration getPublicTokenTtl() {
        return publicTokenTtl;
    }

    public void setPublicTokenTtl(Duration publicTokenTtl) {
        this.publicTokenTtl = publicTokenTtl;
    }

    public Duration getExpirySweepInterval() {
        return expirySweepInterval;
    }

    public void setExpirySweepInterval(Duration expirySweepInterval) {
        this.expirySweepInterval = expirySweepInterval;
    }

    public Integer getExpirySweepBatchSize() {
        return expirySweepBatchSize;
    }

    public void setExpirySweepBatchSize(Integer expirySweepBatchSize) {
        this.expirySweepBatchSize = expirySweepBatchSize;
    }

    public Integer getTradesPageSize() {
        return tradesPageSize;
    }

    public void setTradesPageSize(Integer tradesPageSize) {
        this.tradesPageSize = tradesPageSize;
    }
}
