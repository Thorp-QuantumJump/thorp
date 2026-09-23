package com.ssafy.thorp.backtest.infrastructure.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 백엔드가 아는 상장일과 워커가 실제로 읽는 자료의 첫 거래일이 어긋난다는 사실을 붙박는다.
 *
 * <p><b>이 어긋남이 워밍업 결함의 뿌리다.</b> {@code EffectivePeriodCalculator}는 {@code
 * contracts/evaluation-window.md}의 {@code firstEvaluable}을 <b>상장일</b>에서 센다. 그런데 워커가 읽는 자료는 상장일이 아니라
 * <b>데이터셋이 시작한 날</b>부터 있다. 둘이 어긋나면 백엔드가 "여기서 시작하면 워밍업을 채울 수 있다"고 판정한 날에 워커는 채울 자료가 없다.
 *
 * <p>계약이 요구하는 기준점은 사실 {@code max(상장일, 데이터셋 첫 거래일)}이다. 지금 {@link
 * com.ssafy.thorp.backtest.application.port.MarketMetadataPort}에는 <b>자료 시작일을 묻는 길이 없어</b> 뒤쪽 항을 넣을
 * 수 없다. 그래서 워커 {@code engine_runner.evaluation_start()}가 읽어 온 달력으로 되밀어 막고 있고, 그만큼 화면에 보이는 기간과 실제 평가
 * 기간이 갈릴 수 있다. 001의 자료 계층이 들어올 때 함께 옮긴다(001 {@code tasks.md} T029).
 *
 * <p>이 검사는 어긋남을 <b>고치지 않고 드러낸다.</b> 두 값이 같아지면(=001이 자료 시작일을 포트에 실으면) 여기가 빨개지고, 그때가 되밀기를 걷어내고 백엔드
 * 기준점을 고칠 시점이다. gradle test의 작업 디렉터리는 backend/이고, 이 파일은 {@code build.gradle}의 {@code :test} 입력으로
 * 선언되어 있다 — 선언하지 않으면 픽스처만 바뀌었을 때 검사가 조용히 건너뛰어진다.
 */
class MarketFixtureAlignmentTest {

    /** 워커가 실제로 읽는 자료. {@code CsvMarketDataProvider}가 쓰는 바로 그 파일이다. */
    private static final Path WORKER_PRICES =
            Path.of("..", "worker", "examples", "three_etf_24m", "prices_raw.csv");

    private static final String SEMICONDUCTOR = "091160.XKRX";
    private static final String BATTERY = "305720.XKRX";

    private final FixtureMarketMetadataAdapter adapter =
            new FixtureMarketMetadataAdapter(LocalDate.of(2026, 12, 31));

    /** 픽스처 자료가 실제로 시작하는 날. 파일에서 읽는다 — 상수로 베껴 두면 파일이 바뀌어도 따라오지 않는다. */
    private static LocalDate firstDataDay() throws IOException {
        try (Stream<String> lines = Files.lines(WORKER_PRICES)) {
            return lines.skip(1)
                    .map(line -> line.split(","))
                    .filter(cells -> cells.length > 1)
                    .map(cells -> LocalDate.parse(cells[1]))
                    .min(LocalDate::compareTo)
                    .orElseThrow(
                            () -> new IllegalStateException("픽스처에 가격 행이 없습니다: " + WORKER_PRICES));
        }
    }

    @Test
    @DisplayName("워커 픽스처의 첫 거래일이 어댑터 상장일보다 한참 뒤다 — 워밍업 결함의 뿌리")
    void listingDatesPredateTheDataByYears() throws IOException {
        LocalDate firstData = firstDataDay();

        assertThat(firstData).isEqualTo(LocalDate.of(2023, 1, 2));
        for (String instrumentId : List.of(SEMICONDUCTOR, BATTERY)) {
            LocalDate listedOn = adapter.listedOn(instrumentId).orElseThrow();
            assertThat(listedOn)
                    .as("%s 상장일이 자료 시작일보다 앞선다 — 그 사이에는 읽을 가격이 없다", instrumentId)
                    .isBefore(firstData);
        }
        assertThat(adapter.listedOn(SEMICONDUCTOR).orElseThrow())
                .isEqualTo(LocalDate.of(2015, 1, 2));
        assertThat(adapter.listedOn(BATTERY).orElseThrow()).isEqualTo(LocalDate.of(2018, 9, 12));
    }

    @Test
    @DisplayName("그래서 백엔드가 낸 effectiveStart 앞에 워밍업 자료가 없을 수 있다 — 워커가 되밀어 막는다")
    void backendCannotGuaranteeWarmupBeforeItsOwnEffectiveStart() throws IOException {
        LocalDate firstData = firstDataDay();
        // 두 종목 모두 2018년 이전 상장이므로 warmup 63거래일을 얹어도 commonStart 는 2019년 이전이다.
        Optional<LocalDate> commonStart =
                adapter.evaluationStart(adapter.listedOn(BATTERY).orElseThrow(), 63);
        assertThat(commonStart).isPresent();
        assertThat(commonStart.get()).isBefore(firstData);

        // 그래서 사용자가 자료 첫날 근처를 고르면 effectiveStart = 요청 시작일이 그대로 나간다.
        // 백엔드는 그 앞에 63거래일이 있는지 알지 못한다 — 실제로는 한 달치도 없다.
        LocalDate requested = LocalDate.of(2023, 2, 1);
        assertThat(requested).isAfter(commonStart.get());
        assertThat(requested).isBefore(firstData.plusMonths(3));
    }
}
