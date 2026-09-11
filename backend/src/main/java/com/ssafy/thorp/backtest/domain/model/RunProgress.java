package com.ssafy.thorp.backtest.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 실행의 진행 정도(spec 004 Clarify Q4, FR-001). 화면은 단계 이름과 함께 "처리한 마지막 거래일"과 전체 대비 비율을 보여준다.
 *
 * <p>시계열은 담지 않는다. PROGRESS 이벤트의 포인트는 SSE로만 흘리고 DB에는 여기 네 값만 남긴다(R-05) — 진행 시계열을 저장하면 최종 결과의 시계열과 두
 * 벌이 되고 둘이 어긋날 수 있다.
 *
 * @param lastTradingDate 처리한 마지막 거래일. 취소·한도로 끊겼을 때 완료 범위가 된다
 * @param completedTradingDays null = 아직 계산 단계가 아니다(PREPARING_DATA)
 * @param totalTradingDays null = 위와 같음
 */
public record RunProgress(
        ProgressStage stage,
        LocalDate lastTradingDate,
        Integer completedTradingDays,
        Integer totalTradingDays) {

    public RunProgress {
        Objects.requireNonNull(stage, "stage must not be null");
        if (completedTradingDays != null && completedTradingDays < 0) {
            throw new IllegalArgumentException(
                    "completedTradingDays must not be negative: " + completedTradingDays);
        }
        if (totalTradingDays != null && totalTradingDays <= 0) {
            throw new IllegalArgumentException(
                    "totalTradingDays must be positive: " + totalTradingDays);
        }
        if (completedTradingDays != null
                && totalTradingDays != null
                && completedTradingDays > totalTradingDays) {
            throw new IllegalArgumentException(
                    "completedTradingDays must not exceed totalTradingDays: "
                            + completedTradingDays
                            + " > "
                            + totalTradingDays);
        }
    }

    /** 자료 준비 단계. 거래일 수는 아직 모른다. */
    public static RunProgress preparingData() {
        return new RunProgress(ProgressStage.PREPARING_DATA, null, null, null);
    }
}
