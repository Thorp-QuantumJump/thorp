package com.ssafy.thorp.regimelink.infrastructure.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 구간 입력 조립 중 보유 스냅샷 규칙 (spec 011 T103, 계약 "구간 입력의 출처와 제약"). */
class BacktestRunSliceReaderTest {

    private static final LocalDate D0 = LocalDate.of(2024, 3, 4);
    private static final LocalDate LAST = LocalDate.of(2024, 3, 8);

    @Test
    @DisplayName("구간 안 004 스냅샷 수와 dailyPositions 행 수가 같다 — 기업행위만 있는 날도 빼지 않는다")
    void everySnapshotInsideTheWindowIsKept() {
        List<EngineOutput.PortfolioSnapshot> snapshots =
                List.of(
                        snapshot(LocalDate.of(2024, 3, 1), "10"), // 기준일 — 구간 밖
                        snapshot(D0, "10"), // 리밸런싱
                        snapshot(LocalDate.of(2024, 3, 5), "20"), // 2:1 분할로만 수량이 바뀐 날
                        snapshot(LocalDate.of(2024, 3, 7), "20"), // 수량이 같아도 스냅샷이면 담는다
                        snapshot(LAST, "25"),
                        snapshot(LocalDate.of(2024, 3, 11), "25")); // 구간 뒤

        List<RegimeLinkJob.DailyPositions> days =
                BacktestRunSliceReader.dailyPositions(snapshots, D0, LAST);

        long inside =
                snapshots.stream()
                        .filter(s -> !s.date().isBefore(D0) && !s.date().isAfter(LAST))
                        .count();
        assertThat(days).hasSize((int) inside);
        assertThat(days)
                .extracting(RegimeLinkJob.DailyPositions::date)
                .containsExactly(D0, LocalDate.of(2024, 3, 5), LocalDate.of(2024, 3, 7), LAST);
        assertThat(days.get(1).positions())
                .singleElement()
                .satisfies(position -> assertThat(position.quantity()).isEqualTo(20L));
    }

    private static EngineOutput.PortfolioSnapshot snapshot(LocalDate date, String quantity) {
        return new EngineOutput.PortfolioSnapshot(
                date,
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(
                        new EngineOutput.PositionSnapshot(
                                "244580.XKRX",
                                new BigDecimal(quantity),
                                new BigDecimal("1000"),
                                BigDecimal.ONE,
                                BigDecimal.ZERO)),
                List.of());
    }
}
