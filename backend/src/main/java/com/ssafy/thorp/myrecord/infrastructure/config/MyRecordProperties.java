package com.ssafy.thorp.myrecord.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 내 기록 설정(spec 006 T002, FR-018). {@code thorp.my-records.*}
 *
 * <p><b>값이 아니라 장치가 산출물이다.</b> 탐색 묶음 상한의 확정값은 PD-13이 실측 뒤 정하고, 그때까지 들어 있는 수는 임시값이다. spec은 확정값을 고정하지
 * 않는다.
 *
 * <p>{@code @NotNull}이라 값이 비면 애플리케이션이 기동에 실패한다 — 설정 누락으로 "상한 없이 저장"이 되지 않게 하려는 것이다. {@code
 * BacktestLimitProperties}와 같은 규약이다.
 */
@Validated
@ConfigurationProperties(prefix = "thorp.my-records")
public class MyRecordProperties {

    /**
     * 한 사용자가 저장해 둘 수 있는 탐색 묶음 기록 수.
     *
     * <p>상한은 탐색 묶음에만 있다 — 단일 분석·경제환경 기록에는 개수 상한이 없다(FR-018). 삭제된 항목은 세지 않는다.
     */
    @NotNull
    @Min(1)
    private Integer searchBundleLimit;

    public Integer getSearchBundleLimit() {
        return searchBundleLimit;
    }

    public void setSearchBundleLimit(Integer searchBundleLimit) {
        this.searchBundleLimit = searchBundleLimit;
    }
}
